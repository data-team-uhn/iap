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
package io.uhndata.iap.submissions.internal.handlers;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.internal.ApplicableItems;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.ReferenceUtils;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that records a submitter's answers.
 *
 * <p>Each payload entry names a question by its path relative to the schema version, such as
 * {@code details/startDate}, and carries the answer's value or values. An entry naming no question of the schema
 * version is refused.</p>
 *
 * <p>Saving is idempotent. An answer already recorded for a question is found by its reference to the question, and
 * updated in place, so saving twice leaves one answer.</p>
 *
 * <p>A value is accepted only for a question the submitter is shown. This is judged after the save's own writes, so a
 * question the same save reveals is accepted along with the answer that reveals it. Clearing an answer is always
 * accepted.</p>
 *
 * <p>Only the person who raised the submission may save, and only while it is a draft. The start event also names
 * {@code @creator} as its performer, but administrators pass every performer check, so the first rule is checked
 * again here.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class SaveAnswersHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "saveAnswers";

    private static final String QUESTION_PROPERTY = "question";

    private static final String VALUE_PROPERTY = "value";

    @Reference
    private ConditionEvaluator conditions;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Submission submission = SubmissionHost.read(target, HANDLER_NAME);
        checkMayEdit(submission, context.getActor());
        final Resource schemaVersion = schemaVersionOf(submission, target);
        final Map<Resource, String[]> answers = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : context.getEvent().getPayload().entrySet()) {
            answers.put(question(schemaVersion, entry.getKey()), values(entry.getKey(), entry.getValue()));
        }
        final Map<String, String> existing = answersByQuestion(submission);
        VersioningUtils.checkOut(target);
        for (final Map.Entry<Resource, String[]> answer : answers.entrySet()) {
            record(existing, target, answer.getKey(), answer.getValue());
        }
        checkShown(target, schemaVersion, answers);
        context.recordRole(target, "answered");
    }

    /**
     * Refuses the save if it gave a value to a question the submitter is not shown, now that its own answers are
     * written. The engine then commits nothing.
     *
     * @param target the submission's resource, read again to see the answers just written
     * @param schemaVersion the schema version the question paths are relative to
     * @param answers the questions this save answered, with the values it gave them
     * @throws InvalidStateException when a value was given to a question that does not apply
     */
    private void checkShown(final Resource target, final Resource schemaVersion, final Map<Resource, String[]> answers)
        throws InvalidStateException
    {
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "A submission resource always reads as a submission");
        final Set<String> shown = ApplicableItems.getQuestions(submission, this.conditions).stream()
            .map(Question::getPath)
            .collect(Collectors.toSet());
        final List<String> hidden = answers.entrySet().stream()
            .filter(answer -> answer.getValue().length > 0 && !shown.contains(answer.getKey().getPath()))
            .map(answer -> answer.getKey().getPath().substring(schemaVersion.getPath().length() + 1))
            .toList();
        if (!hidden.isEmpty()) {
            throw new InvalidStateException("These questions do not apply to this request now: "
                + String.join(", ", hidden));
        }
    }

    /**
     * Refuses a change to a submission that is not its submitter's, or that comes after it stopped being a draft.
     *
     * @param submission the submission being changed
     * @param actor the user id of whoever is changing it
     * @throws NotAuthorizedException when somebody else is changing it
     * @throws InvalidStateException when it is no longer a draft
     */
    static void checkMayEdit(final Submission submission, final String actor)
        throws NotAuthorizedException, InvalidStateException
    {
        // getCreatedBy prefers who the engine recorded over jcr:createdBy, which names the engine's own service user
        if (!actor.equals(submission.getCreatedBy())) {
            throw new NotAuthorizedException("Only the person who raised a request may change it");
        }
        if (!submission.isDraft()) {
            throw new InvalidStateException("This request has been submitted and can no longer be changed");
        }
    }

    /**
     * The schema version whose questions this submission may answer.
     *
     * @param submission the submission being answered
     * @param target the submission's resource, read through the resolver the save writes with
     * @return the schema version's resource
     * @throws InvalidPayloadException when this session cannot read the schema version
     */
    private Resource schemaVersionOf(final Submission submission, final Resource target)
        throws InvalidPayloadException
    {
        final SchemaVersion schemaVersion = submission.findSchemaVersion();
        if (schemaVersion == null) {
            throw new InvalidPayloadException("Cannot identify the schema of this submission");
        }
        // Read again through the resolver the save writes with
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
     * @param path the question's path relative to that version, such as {@code details/startDate}
     * @return the question's resource
     * @throws InvalidPayloadException when nothing at that path is a question of this schema version
     */
    private Resource question(final Resource schemaVersion, final String path) throws InvalidPayloadException
    {
        final Resource question = schemaVersion.getChild(path);
        // Containment is checked on the resolved path, not on the key. `getChild` resolves an absolute key as it is,
        // and normalises `..` out of a relative one, so a key can name a question of another schema. The answer's
        // REFERENCE would then keep that question from being deleted.
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
     * @param question the payload key, for the message
     * @param submitted the payload value
     * @return the values to store, blanks dropped; empty when the answer is being cleared
     * @throws InvalidPayloadException when the value is neither a string nor an array of strings
     */
    private String[] values(final String question, final Object submitted) throws InvalidPayloadException
    {
        if (submitted instanceof String) {
            return withoutBlanks(new String[] {(String) submitted});
        }
        if (submitted instanceof String[]) {
            return withoutBlanks((String[]) submitted);
        }
        throw new InvalidPayloadException("The answer to " + question + " must be text");
    }

    /**
     * Drops the blank values. A cleared field posts one empty value, and an answer left with no values is cleared.
     *
     * @param given the values as the payload carried them
     * @return the non-blank values, in the order given
     */
    private String[] withoutBlanks(final String[] given)
    {
        return Arrays.stream(given).filter(value -> !value.isBlank()).toArray(String[]::new);
    }

    /**
     * Writes one answer, updating the one already there if this question has been answered before.
     *
     * @param answers the answers already in the submission: the path of each answer node, by the path of its question
     * @param target the submission's resource, which the answers are children of
     * @param question the question being answered
     * @param values the submitted values
     * @throws PersistenceException when the answer cannot be written
     */
    private void record(final Map<String, String> answers, final Resource target, final Resource question,
        final String[] values) throws PersistenceException
    {
        final String existing = answers.get(question.getPath());
        if (existing == null && values.length == 0) {
            // Clearing a question that has no answer yet. The createParts task creates its empty answer.
            return;
        }
        if (existing != null) {
            final Resource answer = Objects.requireNonNull(target.getResourceResolver().getResource(existing),
                "An answer the submission just reported is still where it said");
            // What the form offered is read before the save changes the answer it is compared with
            final List<String> offered = SuggestionDistance.getOffered(answer);
            modifiable(answer).put(VALUE_PROPERTY, values);
            // Only an answer that already exists can hold a suggestion, since a reading writes under an answer
            SuggestionDistance.record(answer, values, offered);
            return;
        }
        final Resource answer = target.getResourceResolver().create(target, UUID.randomUUID().toString(),
            Map.of("jcr:primaryType", "sub:Answer", VALUE_PROPERTY, values));
        ReferenceUtils.setReference(answer, QUESTION_PROPERTY, question);
    }

    /**
     * The answers this submission already holds, by question. Of two answers to one question, the first is kept.
     *
     * @param submission the submission to look in
     * @return the path of each answer node, by the path of its question
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

    private ModifiableValueMap modifiable(final Resource resource)
    {
        return Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class),
            "The engine writes through a session that can modify what it was given");
    }
}
