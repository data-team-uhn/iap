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
package io.uhndata.iap.extraction.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that reads every extractable answer out of a proposal and records it: a {@code sub:Answer}
 * holding the value, with a {@code sub:Extraction} under it saying where it came from and how sure the model was,
 * and a {@code sub:Evidence} for each quote. A question the submitter has already answered is left alone - what a
 * person said stands over what a model read.
 *
 * <p>Fields are keyed by the question's path within its schema version, so two sections may both have a
 * {@code title}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class IntakeAnswersHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "intakeAnswers";

    @Reference
    private AnswerIntakeService intake;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        if (!GateProposalHandler.passed(context)) {
            return;
        }
        final Resource target = context.getTarget();
        final File file = GateProposalHandler.gatedFile(context);
        if (file == null) {
            ExtractionStatus.record(target, ExtractionStatus.FAILED, "The document the gate read is gone");
            return;
        }
        final Submission submission = SubmissionFiles.submission(target);
        final Map<String, Question> questions = extractable(submission);
        if (questions.isEmpty()) {
            ExtractionStatus.record(target, ExtractionStatus.DONE, "The schema asks nothing of the document");
            return;
        }
        final IntakeResult result = ask(context, file, questions);
        if (result.degraded()) {
            ExtractionStatus.record(target, ExtractionStatus.FAILED, "The model's answer could not be read");
            return;
        }
        record(context.getResourceResolver(), target, submission, questions, result, file);
        ExtractionStatus.record(target, ExtractionStatus.DONE, null);
    }

    /**
     * Put the questions to the model, and write the tags it gave the chunks it read.
     */
    private IntakeResult ask(final WorkflowTaskContext context, final File file,
        final Map<String, Question> questions) throws PersistenceException
    {
        final List<ExtractionField> fields = new ArrayList<>();
        questions.forEach((key, question) -> fields.add(ExtractionField.of(question, key)));
        final IntakeResult result;
        final long started = System.nanoTime();
        try {
            result = this.intake.run(file, fields);
        } catch (final IOException e) {
            throw new PersistenceException("Could not read the parsed document: " + e.getMessage(), e);
        }
        final Resource fileResource = context.getResourceResolver().getResource(file.getPath());
        if (fileResource != null) {
            this.intake.applyTags(fileResource, result);
            // Recorded even for a degraded run: those chunks were read and paid for, and a later pass that
            // did not know would read them again for nothing.
            LlmCallTracker.append(fileResource, LlmCallTracker.INTAKE, new ArrayList<>(questions.keySet()),
                result.sentChunkIds(), LlmCallTracker.elapsedMs(started),
                result.degraded() ? LlmCallTracker.DEGRADED : LlmCallTracker.OK);
        }
        return result;
    }

    /**
     * Record every answer the model found, for the questions the submitter has not answered themselves.
     */
    private void record(final ResourceResolver resolver, final Resource target, final Submission submission,
        final Map<String, Question> questions, final IntakeResult result, final File file)
        throws PersistenceException
    {
        final Map<String, Answer> answered = answered(submission);
        for (final Map.Entry<String, FieldResult> entry : result.fields().entrySet()) {
            final Question question = questions.get(entry.getKey());
            if (entry.getValue().found() && question != null && !answered.containsKey(question.getPath())) {
                ExtractedAnswers.write(resolver, target, question, entry.getValue(), file);
            }
        }
    }

    /**
     * The questions worth putting to the model, by their path within the schema version.
     */
    private static Map<String, Question> extractable(final Submission submission)
    {
        final String versionPath = submission.getSchemaVersion().getPath();
        final Map<String, Question> questions = new HashMap<>();
        for (final Question question : submission.getQuestions()) {
            if (ExtractionField.isExtractable(question)) {
                questions.put(question.getPath().substring(versionPath.length() + 1), question);
            }
        }
        return questions;
    }

    /**
     * What the submitter has already answered, by question path.
     */
    private static Map<String, Answer> answered(final Submission submission)
    {
        final Map<String, Answer> answered = new HashMap<>();
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            if (question != null) {
                answered.put(question.getPath(), answer);
            }
        }
        return answered;
    }

}
