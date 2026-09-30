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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that records a submitter's answers.
 *
 * <p>Each payload entry names a question by its path <em>relative to the schema version</em>, and carries the answer's
 * value or values. An entry pointing to a non-existing question is refused rather than quietly stored.</p>
 *
 * <p>Saving is idempotent: an answer already recorded for a question is updated in place, found by the reference it
 * holds rather than by any name, so saving twice leaves one answer and not two.</p>
 *
 * <p><strong>Both rules about who may do this are enforced here:</strong> the actor is the person the engine recorded
 * as having raised the submission, and that the submission is still a draft. The first rule is checked here explicitly
 * rather than declared, because {@code PerformerCheck} does not yet support the {@code @creator} role.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class SaveAnswersHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "saveAnswers";

    private static final String QUESTION_PROPERTY = "question";

    private static final String VALUE_PROPERTY = "value";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        if (!target.isResourceType(Submission.RESOURCE_TYPE)) {
            throw new WorkflowDefinitionException("The save workflow only applies to submissions, not to "
                + target.getResourceType());
        }
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "A submission resource always reads as a submission");
        checkMayEdit(submission, context.getActor());
        final Resource schemaVersion = schemaVersionOf(submission, target);
        final Map<Resource, String[]> answers = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : context.getEvent().getPayload().entrySet()) {
            answers.put(question(schemaVersion, entry.getKey()), values(entry.getKey(), entry.getValue()));
        }
        final Map<String, String> existing = answersByQuestion(submission);
        for (final Map.Entry<Resource, String[]> answer : answers.entrySet()) {
            record(existing, target, answer.getKey(), answer.getValue());
        }
    }

    /**
     * Refuses a save that is not the submitter's own, or that comes too late.
     *
     * @param submission the submission being edited
     * @param actor the user whose action this is
     * @throws NotAuthorizedException when somebody else is editing it
     * @throws InvalidStateException when it is no longer a draft
     */
    private void checkMayEdit(final Submission submission, final String actor)
        throws NotAuthorizedException, InvalidStateException
    {
        // getCreatedBy prefers what the engine recorded over jcr:createdBy, which names the engine's own service
        // user for everything it writes
        if (!actor.equals(submission.getCreatedBy())) {
            throw new NotAuthorizedException("Only the person who raised a request may answer it");
        }
        if (!submission.isDraft()) {
            throw new InvalidStateException("This request has been submitted and can no longer be changed");
        }
    }

    /**
     * The schema version whose questions this submission may answer.
     *
     * @param submission the submission being edited
     * @param target the submission's own resource, read through the session everything else uses
     * @return the schema version's resource
     * @throws InvalidPayloadException when the submission answers nothing readable
     */
    private Resource schemaVersionOf(final Submission submission, final Resource target)
        throws InvalidPayloadException
    {
        // The node type makes the reference mandatory, which is a rule about the content and not a promise to
        // every reader: a version that has gone, or one this session may not read, resolves to nothing
        final SchemaVersion schemaVersion = submission.findSchemaVersion();
        if (schemaVersion == null) {
            throw new InvalidPayloadException("Cannot identify the schema of this submission");
        }
        // Resolved again on the session everything else uses. The model reads through the same resolver in
        // production, but not in every harness, and a version that adapts without resolving here is still a
        // submission that cannot say what it is answering
        final Resource resource = target.getResourceResolver().getResource(schemaVersion.getPath());
        if (resource == null) {
            throw new InvalidPayloadException("Cannot access the schema of this submission");
        }
        return resource;
    }

    /**
     * Resolves one payload key into the question it names.
     *
     * @param schemaVersion the schema version the paths are relative to
     * @param path the question's path relative to that version
     * @return the question's resource
     * @throws InvalidPayloadException when nothing of that name is a question of this schema version
     */
    private Resource question(final Resource schemaVersion, final String path) throws InvalidPayloadException
    {
        final Resource question = schemaVersion.getChild(path);
        // Containment is checked on what the path resolved to, not on the path as written. `getChild` hands an
        // absolute path straight to the resolver and normalises `..` out of a relative one, so a key can name a
        // question of some other schema -- one the caller may not even be able to read, since the engine's
        // session can. The answer would then hold a REFERENCE that makes that question undeletable.
        if (question == null || !question.isResourceType(Question.RESOURCE_TYPE)
            || !question.getPath().startsWith(schemaVersion.getPath() + "/")) {
            throw new InvalidPayloadException("There is no question " + path + " to answer in this request");
        }
        return question;
    }

    /**
     * One answer's values, as the request gave them: a single parameter arrives as a string, a repeated one as an
     * array.
     *
     * @param submitted the payload value
     * @return the values to store, blanks dropped, empty when the answer is being cleared
     */
    private String[] values(final String question, final Object submitted) throws InvalidPayloadException
    {
        if (submitted instanceof String) {
            return clearing(new String[] {(String) submitted});
        }
        if (submitted instanceof String[]) {
            return clearing((String[]) submitted);
        }
        throw new InvalidPayloadException("The answer to " + question + " must be text");
    }

    /**
     * Reads the clear sentinel, leaving every other answer exactly as it was given.
     *
     * <p>A cleared field posts one empty value, because naming the question is the only way to say "clear this".</p>
     *
     * <p>Only that exact shape. Dropping every blank instead would be a wider rule than the sentinel, and would
     * quietly make an option whose value is the empty string impossible to record.</p>
     *
     * @param given the values as the payload carried them
     * @return an empty array if this is the sentinel, otherwise {@code given} unchanged
     */
    private String[] clearing(final String[] given)
    {
        return given.length == 1 && given[0].isEmpty() ? new String[0] : given;
    }

    /**
     * Writes one answer, updating the one already there if this question has been answered before.
     *
     * @param answers the answers already present in the submission, as a map from the path of the question
     *     they answer to the path of the existing answer node
     * @param target the submission's own resource, which the answers are children of
     * @param question the question being answered
     * @param values the submitted values
     * @throws PersistenceException when the answer cannot be written
     */
    private void record(final Map<String, String> answers, final Resource target, final Resource question,
        final String[] values) throws PersistenceException
    {
        final String existing = answers.get(question.getPath());
        if (existing == null && values.length == 0) {
            // Clearing a question nobody has answered. Creating the node anyway would store nothing, count
            // towards the answers the submission reports, and hold its question against deletion for ever
            return;
        }
        if (existing != null) {
            modifiable(Objects.requireNonNull(target.getResourceResolver().getResource(existing),
                "An answer the submission just reported is still where it said")).put(VALUE_PROPERTY, values);
            return;
        }
        final Resource answer = target.getResourceResolver().create(target, UUID.randomUUID().toString(),
            Map.of("jcr:primaryType", "sub:Answer", VALUE_PROPERTY, values));
        reference(answer, question);
    }

    /**
     * Gather the questions this submission already holds an answer for.
     *
     * @param submission the submission to look in
     * @return a map from questions to existing answers, as repository paths
     */
    private Map<String, String> answersByQuestion(final Submission submission)
    {
        final Map<String, String> byQuestion = new HashMap<>();
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            if (question != null) {
                byQuestion.putIfAbsent(question.getPath(), answer.getPath());
            }
        }
        return byQuestion;
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
            answerNode.setProperty(QUESTION_PROPERTY, questionNode);
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
