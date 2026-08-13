/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.uhndata.iap.submissions.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that records a submitter's answers: what the save workflow on a {@code sub:Submission} performs.
 *
 * <p>Each payload entry names a question by its path <em>relative to the schema version</em> — the same way a
 * condition names the question it depends on — and carries the answer's value or values. Addressing questions that
 * way means the editor never has to know where the schema version lives, and an entry naming something that is not
 * a question of this submission's schema is refused rather than quietly stored.</p>
 *
 * <p>Saving is idempotent: an answer already recorded for a question is updated in place, found by the reference it
 * holds rather than by any name, so saving twice leaves one answer and not two.</p>
 *
 * <p><strong>Both rules about who may do this are enforced here, in full.</strong> The engine executes with its own
 * privileged session, so nothing downstream will refuse anyone: whatever is not checked in a handler is allowed.
 * They are that the actor is the person the engine recorded as having raised the submission, and that the submission
 * is still a draft. A deployment that lets a coordinator fill requests in on someone's behalf changes this check;
 * neither rule can be written as a performer, because performers name groups, not "whoever raised this one".</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class SaveAnswersHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "saveAnswers";

    /** The only lifecycle in which a submission may still be written to by its submitter. */
    private static final String DRAFT = "draft";

    private static final String QUESTION = "question";

    private static final String VALUE = "value";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "The save workflow only applies to submissions");
        checkMayEdit(submission, context.getActor());
        final Resource version = versionOf(submission, target);
        for (final Map.Entry<String, Object> entry : context.getEvent().getPayload().entrySet()) {
            final Resource question = question(version, entry.getKey());
            final String[] values = values(entry.getValue());
            checkOffered(question, values);
            record(submission, target, question, values);
        }
    }

    /**
     * Refuses a save that is not the submitter's own, or that comes too late.
     *
     * @param submission the submission being edited
     * @param actor the user whose action this is
     * @throws NotAuthorizedException when somebody else is editing it, or it is no longer a draft
     */
    private void checkMayEdit(final Submission submission, final String actor) throws NotAuthorizedException
    {
        // getCreatedBy prefers what the engine recorded over jcr:createdBy, which names the engine's own service
        // user for everything it writes
        if (!actor.equals(submission.getCreatedBy())) {
            throw new NotAuthorizedException("Only the person who raised a request may answer it");
        }
        if (!submission.getTags().contains(DRAFT)) {
            throw new NotAuthorizedException("This request has been submitted and can no longer be changed");
        }
    }

    /**
     * The schema version whose questions this submission may answer.
     *
     * @param submission the submission being edited
     * @param target the submission's own resource, read through the session everything else uses
     * @return the version's resource
     * @throws InvalidPayloadException when the submission answers nothing readable
     */
    private Resource versionOf(final Submission submission, final Resource target) throws InvalidPayloadException
    {
        // The node type makes the reference mandatory and the model declares it non-null, so what can go wrong is
        // not that there is no version but that this session cannot read the one there is
        final SchemaVersion version = submission.getSchemaVersion();
        final Resource resource = target.getResourceResolver().getResource(version.getPath());
        if (resource == null) {
            throw new InvalidPayloadException("This request does not say what it is answering");
        }
        return resource;
    }

    /**
     * Resolves one payload key into the question it names.
     *
     * @param version the schema version the paths are relative to
     * @param path the question's path relative to that version
     * @return the question's resource
     * @throws InvalidPayloadException when nothing of that name is a question of this schema version
     */
    private Resource question(final Resource version, final String path) throws InvalidPayloadException
    {
        final Resource question = version.getChild(path);
        if (question == null || !question.isResourceType(Question.RESOURCE_TYPE)) {
            throw new InvalidPayloadException("There is no question " + path + " to answer in this request");
        }
        return question;
    }

    /**
     * Refuses a value a question offering a fixed set of answers does not offer.
     *
     * <p>Checked here, unlike the {@code dataType}, because the difference is what the submitter can see: the form
     * states the answers this question offers, so refusing one it does not is a refusal they can act on, whereas a
     * type mismatch would be a refusal for a reason the form never showed them. It matters beyond tidiness — an
     * option's value is what conditions compare against, so a value from outside the set would make a request
     * ask questions nobody chose.</p>
     *
     * @param question the question being answered
     * @param values the submitted values; empty ones clear the answer and are always allowed
     * @throws InvalidPayloadException when a value is not one of the offered options
     */
    private void checkOffered(final Resource question, final String[] values) throws InvalidPayloadException
    {
        // Adapting cannot fail here: the caller has already established that this resource is a question
        final List<AnswerOption> options = Objects.requireNonNull(question.adaptTo(Question.class),
            "A question that does not read as one").getOptions();
        if (options.isEmpty()) {
            // Answered freely, in whatever the data type accepts
            return;
        }
        final Set<String> offered = options.stream().map(AnswerOption::getValue).collect(Collectors.toSet());
        for (final String value : values) {
            if (!value.isEmpty() && !offered.contains(value)) {
                throw new InvalidPayloadException(
                    "\"" + value + "\" is not one of the answers this question offers");
            }
        }
    }

    /**
     * One answer's values, as the request gave them: a single parameter arrives as a string, a repeated one as an
     * array, and a question that may hold several values is answered by repeating it.
     *
     * @param submitted the payload value
     * @return the values to store
     */
    private String[] values(final Object submitted)
    {
        return submitted instanceof String[] ? (String[]) submitted : new String[] {String.valueOf(submitted)};
    }

    /**
     * Writes one answer, updating the one already there if this question has been answered before.
     *
     * <p>The values are stored as strings, which is what a request carries. Interpreting them according to the
     * question's {@code dataType} is a separate job from recording them, and doing it here would mean a save could
     * be refused for a reason the submitter cannot see in the form they filled in.</p>
     *
     * @param submission the submission being edited
     * @param target the submission's own resource, which the answers are children of
     * @param question the question being answered
     * @param values the submitted values
     * @throws PersistenceException when the answer cannot be written
     */
    private void record(final Submission submission, final Resource target, final Resource question,
        final String[] values) throws PersistenceException
    {
        final String existing = answered(submission, question);
        if (existing != null) {
            modifiable(Objects.requireNonNull(target.getResourceResolver().getResource(existing),
                "An answer the submission just reported is still where it said")).put(VALUE, values);
            return;
        }
        final Resource answer = target.getResourceResolver().create(target, UUID.randomUUID().toString(),
            Map.of("jcr:primaryType", "sub:Answer", VALUE, values));
        reference(answer, question);
    }

    /**
     * Where this submission already holds an answer for a question, found by the reference the answer carries
     * rather than by any name, so that saving the same form twice updates one answer instead of adding another.
     *
     * <p>An answer whose question no longer resolves is passed over: a question removed from the schema leaves one
     * behind, and it is not the answer to anything being saved now.</p>
     *
     * @param submission the submission to look in
     * @param question the question to look for
     * @return the existing answer's path, or {@code null} if this question has not been answered yet
     */
    private String answered(final Submission submission, final Resource question)
    {
        return submission.getAnswers().stream()
            // Read once into a local: asking twice is the shape that makes a @Nullable accessor look safe to
            // dereference when it is not, which is exactly what the null detectors are here to catch
            .filter(answer -> {
                final Question answered = answer.getQuestion();
                return answered != null && question.getPath().equals(answered.getPath());
            })
            .map(Answer::getPath)
            .findFirst()
            .orElse(null);
    }

    /**
     * Points a fresh answer at its question with a real {@code REFERENCE}, which has to go through the JCR API: a
     * plain string would carry the right identifier with the wrong type, and the node type rejects it at commit.
     *
     * @param answer the answer just created
     * @param question the question it answers
     * @throws PersistenceException when the repository refuses the reference
     */
    private void reference(final Resource answer, final Resource question) throws PersistenceException
    {
        final Node answerNode = Objects.requireNonNull(answer.adaptTo(Node.class),
            "A freshly created answer is always backed by a JCR node");
        final Node questionNode = Objects.requireNonNull(question.adaptTo(Node.class),
            "A question read from the schema is always backed by a JCR node");
        try {
            answerNode.setProperty(QUESTION, questionNode);
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not reference the question", e);
        }
    }

    private ModifiableValueMap modifiable(final Resource resource)
    {
        return Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class),
            "The engine writes through a session that can modify what it was given");
    }
}
