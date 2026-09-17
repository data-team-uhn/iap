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
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
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
 * is still a draft. A deployment that lets a coordinator fill requests in on someone's behalf changes this check.
 * The first rule is checked here rather than declared, because {@code PerformerCheck} matches a performer against
 * an authorizable's own id and its groups and does not resolve the {@code @creator} vocabulary yet.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class SaveAnswersHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "saveAnswers";

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
        // Asked of the resource, not of the adaptation: a model registered for one type adapts a resource of an
        // unrelated one, so adaptTo alone would let a misdirected definition through and fail further in
        if (!target.isResourceType(Submission.RESOURCE_TYPE)) {
            throw new WorkflowDefinitionException("The save workflow only applies to submissions, not to "
                + target.getResourceType());
        }
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "A submission resource always reads as a submission");
        checkMayEdit(submission, context.getActor());
        final Resource version = versionOf(submission, target);
        // Resolved in full before anything is written. The payload is a map, so its iteration order is not the
        // order it was sent in and is salted per run; writing as we go would leave an arbitrary prefix of the
        // answers behind when a later key turns out to be bad
        final Map<Resource, String[]> answers = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : context.getEvent().getPayload().entrySet()) {
            answers.put(question(version, entry.getKey()), values(entry.getKey(), entry.getValue()));
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
     * @throws NotAuthorizedException when somebody else is editing it, or it is no longer a draft
     */
    private void checkMayEdit(final Submission submission, final String actor) throws NotAuthorizedException
    {
        // getCreatedBy prefers what the engine recorded over jcr:createdBy, which names the engine's own service
        // user for everything it writes
        if (!actor.equals(submission.getCreatedBy())) {
            throw new NotAuthorizedException("Only the person who raised a request may answer it");
        }
        if (!submission.isDraft()) {
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
        // The node type makes the reference mandatory, which is a rule about the content and not a promise to
        // every reader: a version that has gone, or one this session may not read, resolves to nothing
        final SchemaVersion version = submission.findSchemaVersion();
        if (version == null) {
            throw new InvalidPayloadException("This request does not say what it is answering");
        }
        // Resolved again on the session everything else uses. The model reads through the same resolver in
        // production, but not in every harness, and a version that adapts without resolving here is still a
        // submission that cannot say what it is answering
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
        // Containment is checked on what the path resolved to, not on the path as written. `getChild` hands an
        // absolute path straight to the resolver and normalises `..` out of a relative one, so a key can name a
        // question of some other schema -- one the caller may not even be able to read, since the engine's
        // session can. The answer would then hold a REFERENCE that makes that question undeletable.
        if (question == null || !question.isResourceType(Question.RESOURCE_TYPE)
            || !question.getPath().startsWith(version.getPath() + "/")) {
            throw new InvalidPayloadException("There is no question " + path + " to answer in this request");
        }
        return question;
    }

    /**
     * One answer's values, as the request gave them: a single parameter arrives as a string, a repeated one as an
     * array, and a question that may hold several values is answered by repeating it.
     *
     * @param submitted the payload value
     * @return the values to store, blanks dropped, empty when the answer is being cleared
     */
    private String[] values(final String question, final Object submitted) throws InvalidPayloadException
    {
        // A payload is a Map<String, Object> and anything may put one together, so what arrives is checked
        // rather than converted. String.valueOf would turn a List into the single literal answer "[a, b]" and a
        // null into the four-character answer "null", both of which are non-blank and would read as real
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
     * <p>A cleared field posts one empty value, because naming the question is the only way to say "clear this"
     * -- a question left out of the payload is untouched, not emptied. What it means is that the question now
     * holds nothing, so it stores nothing: the empty string would read as different from the blank field that
     * produced it, and every later visit would save it again.</p>
     *
     * <p>Only that exact shape. Dropping every blank instead would be a wider rule than the sentinel, and would
     * quietly make an option whose value is the empty string impossible to record.</p>
     *
     * @param given the values as the payload carried them
     * @return nothing at all if this is the sentinel, otherwise {@code given} unchanged
     */
    private String[] clearing(final String[] given)
    {
        return given.length == 1 && given[0].isEmpty() ? new String[0] : given;
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
    private Map<String, String> answersByQuestion(final Submission submission)
    {
        // Built once for the whole payload. Asking per entry re-walked the submission's children and
        // dereferenced every answer's REFERENCE through an identifier lookup, to recover a path already held --
        // on the autosave path, inside the engine's open commit.
        final Map<String, String> byQuestion = new HashMap<>();
        for (final Answer answer : submission.getAnswers()) {
            // Read once into a local: asking twice is the shape that makes a @Nullable accessor look safe to
            // dereference when it is not, which is exactly what the null detectors are here to catch
            final Question answered = answer.getQuestion();
            if (answered != null) {
                byQuestion.putIfAbsent(answered.getPath(), answer.getPath());
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
