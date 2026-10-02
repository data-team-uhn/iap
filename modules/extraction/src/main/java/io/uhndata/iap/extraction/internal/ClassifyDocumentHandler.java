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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that classifies uploaded documents: every classification requirement of the schema whose
 * document has been parsed is put to the model, all of them for one document in one call.
 *
 * <p>Each pick is stored as the answer to the classification's question, with its confidence, reasoning and the
 * quotes it rests on, so the submitter sees it pre-filled and can confirm or change it. Whether the workflow acts
 * on it is for the workflow's conditions to decide, through the {@code decision} operand source, which waits for
 * a person when the model was not sure enough.</p>
 *
 * <p>A classification already answered is not asked again, so a second upload does not pay for decisions that
 * were made or confirmed already.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ClassifyDocumentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "classifyDocument";

    private static final Logger LOGGER = LoggerFactory.getLogger(ClassifyDocumentHandler.class);

    @Reference
    private AnswerIntakeService intake;

    @Reference
    private ParsedDocuments documents;

    @Reference
    private ReadingRuns runs;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws PersistenceException
    {
        final Resource target = context.getTarget();
        this.runs.begin(target.getPath());
        try {
            classify(context, target);
        } catch (final ReadingRuns.Stopped e) {
            Thread.interrupted();
            throw new PersistenceException(ExtractionStatus.STOPPED, e);
        } finally {
            this.runs.end(target.getPath());
        }
    }

    /**
     * Classify every parsed document that has classifications still unanswered.
     *
     * @param context the step
     * @param target the submission
     * @throws PersistenceException if an answer cannot be written
     */
    private void classify(final WorkflowTaskContext context, final Resource target) throws PersistenceException
    {
        final Submission submission = SubmissionFiles.submission(target);
        for (final Map.Entry<String, List<ClassificationRequirement>> entry : getOpen(submission).entrySet()) {
            final File file = SubmissionFiles.currentFor(submission, entry.getKey());
            if (file == null) {
                continue;
            }
            if (ParsePropertyNames.STATUS_FAILED.equals(file.getParseStatus())) {
                ExtractionStatus.record(target, ExtractionStatus.FAILED, SubmissionFiles.whyUnreadable(file));
                return;
            }
            if (!ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus())) {
                continue;
            }
            if (!classify(context, submission, file, entry.getValue())) {
                return;
            }
        }
    }

    /**
     * Put one document's classifications to the model and store what it picked.
     *
     * @return {@code false} when the model could not be asked or answered unreadably, which has been recorded
     */
    private boolean classify(final WorkflowTaskContext context, final Submission submission, final File file,
        final List<ClassificationRequirement> classifications) throws PersistenceException
    {
        final Resource target = context.getTarget();
        final String versionPath = submission.getSchemaVersion().getPath();
        final Map<String, Question> questions = new LinkedHashMap<>();
        final List<ExtractionField> fields = new ArrayList<>();
        for (final ClassificationRequirement classification : classifications) {
            final Question question = Objects.requireNonNull(classification.getDecision(),
                "Only a classification holding a question is put to the model");
            final String key = question.getPath().substring(versionPath.length() + 1);
            questions.put(key, question);
            fields.add(ExtractionField.of(question, key, classification.getPrompt()));
        }
        this.runs.setFiles(target.getPath(), Set.of(file.getPath()));
        final long startedAt = System.nanoTime();
        LOGGER.info("Reading step started: step=classify submission={} file={} classifications={}",
            target.getPath(), file.getPath(), fields.size());
        final IntakeResult result = ask(file, fields, getTemplates(classifications));
        if (result == null || result.degraded()) {
            ExtractionStatus.record(target, ExtractionStatus.FAILED,
                result == null ? IntakeResult.UNREADABLE : result.getMessage());
            return false;
        }
        ExtractionFields.write(context.getResourceResolver(), target, submission, questions, result.fields(),
            List.of(file));
        LOGGER.info("Reading step done: step=classify submission={} decided={} of={} ms={}", target.getPath(),
            result.fields().values().stream().filter(FieldResult::found).count(), fields.size(),
            (System.nanoTime() - startedAt) / 1_000_000L);
        return true;
    }

    /**
     * The classifications still to be made, grouped by the document requirement they read. A classification
     * whose condition does not hold, that holds no question, or whose question is already answered is left out.
     */
    private static Map<String, List<ClassificationRequirement>> getOpen(final Submission submission)
    {
        final Set<String> answered = submission.getAnswersByQuestion().keySet();
        final Map<String, List<ClassificationRequirement>> open = new LinkedHashMap<>();
        for (final Requirement requirement : submission.getSchemaVersion().getRequirements()) {
            if (!(requirement instanceof ClassificationRequirement classification)
                || classification.getDocument() == null) {
                continue;
            }
            final List<Question> asked = submission.getQuestions(classification.getName());
            if (asked.isEmpty() || answered.contains(asked.get(0).getPath())) {
                continue;
            }
            open.computeIfAbsent(classification.getDocument(), name -> new ArrayList<>()).add(classification);
        }
        return open;
    }

    /**
     * The reference texts the classifications carry, each under a heading naming what it is for. The model is
     * shown them before its instructions, so the prompt can refer to them.
     */
    private static String getTemplates(final List<ClassificationRequirement> classifications)
    {
        final StringBuilder templates = new StringBuilder();
        for (final ClassificationRequirement classification : classifications) {
            final Resource template = classification.getTemplate();
            if (template == null) {
                continue;
            }
            try {
                templates.append("# Reference for \"").append(classification.getLabel()).append("\"\n\n")
                    .append(DocumentText.readText(template).strip()).append("\n\n");
            } catch (final IOException e) {
                // A classification is still worth making without its reference, which only sharpens it
                LOGGER.warn("The template of {} could not be read: {}", classification.getPath(), e.getMessage());
            }
        }
        return templates.isEmpty() ? null : templates.toString().strip();
    }

    /**
     * Ask the model, or {@code null} when it could not be asked at all.
     */
    private IntakeResult ask(final File file, final List<ExtractionField> fields, final String templates)
    {
        try {
            return this.intake.run(file, this.documents.scan(file), fields, templates);
        } catch (final ReadingRuns.Stopped e) {
            throw e;
        } catch (final IOException e) {
            if (ReadingRuns.isStopped(e)) {
                throw new ReadingRuns.Stopped();
            }
            // Not a failure of the walk: throwing here reverts everything and leaves `running` behind
            LOGGER.warn("The classification could not be asked about {}: {}", file.getPath(), e.getMessage());
            return null;
        }
    }
}
