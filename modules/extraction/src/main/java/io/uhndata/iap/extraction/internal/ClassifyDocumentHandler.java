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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.OfferedOption;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that classifies uploaded documents: every open classification requirement whose document has
 * been parsed is put to the model, all of them for one document together.
 *
 * <p>A sure pick is saved as a suggestion under the classification's question, with its confidence, reasoning and
 * quotes. Sure means confident enough for the classification's threshold, and one of the question's options. The
 * question's value stays empty until the submitter accepts the pick; a workflow gateway reads the pick through the
 * {@code decision} condition source. A pick that is not sure is not saved, so the question is left for the
 * submitter.</p>
 *
 * <p>A classification already answered or picked is not asked again, even of a newer document version, so a second
 * upload does not pay for decisions already made.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ClassifyDocumentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "classifyDocument";

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
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws PersistenceException
    {
        final Resource target = context.getTarget();
        this.runs.begin(target.getPath());
        final boolean stopped;
        try {
            classify(context, target);
        } catch (final ReadingRuns.Stopped e) {
            Thread.interrupted();
            throw new PersistenceException(ExtractionStatus.STOPPED_MESSAGE, e);
        } finally {
            stopped = this.runs.end(target.getPath());
        }
        if (stopped) {
            // The stop landed after the model answered: what it wrote must not be kept
            throw new PersistenceException(ExtractionStatus.STOPPED_MESSAGE, new ReadingRuns.Stopped());
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
        final Submission submission = SubmissionFiles.getSubmission(target);
        for (final Map.Entry<String, List<ClassificationRequirement>> entry : getOpen(submission).entrySet()) {
            final File file = SubmissionFiles.getCurrentFile(submission, entry.getKey());
            if (file == null) {
                continue;
            }
            if (ParsePropertyNames.STATUS_FAILED.equals(file.getParseStatus())) {
                ExtractionStatus.record(target, ExtractionStatus.STATUS_FAILED, SubmissionFiles.describeUnreadable());
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
     * Build the classification prompt, call the model and store what it picked.
     *
     * @return {@code false} when the model was not asked or answered unreadably, which has been recorded
     */
    private boolean classify(final WorkflowTaskContext context, final Submission submission, final File file,
        final List<ClassificationRequirement> classifications) throws PersistenceException
    {
        final Resource target = context.getTarget();
        final String versionPath = submission.getSchemaVersion().getPath();
        final Map<String, Question> questions = new LinkedHashMap<>();
        final Map<String, Double> thresholds = new HashMap<>();
        final List<ExtractionField> fields = new ArrayList<>();
        for (final ClassificationRequirement classification : classifications) {
            final Question question = Objects.requireNonNull(classification.getDecision(),
                "Only a classification holding a question is put to the model");
            final String key = ExtractionField.keyFor(versionPath, question);
            questions.put(key, question);
            thresholds.put(key, classification.getConfidenceThreshold());
            fields.add(ExtractionField.of(key, question, classification.getPrompt()));
        }
        this.runs.setFiles(target.getPath(), Set.of(file.getPath()));
        final long startedAt = System.nanoTime();
        LOGGER.info("Reading step started: step=classify submission={} file={} classifications={}",
            target.getPath(), file.getPath(), fields.size());
        final IntakeResult result = ask(file,
            new ExtractionPrompt.Group(classifications.get(0).getDocument(), fields), getTemplates(classifications));
        if (result.degraded()) {
            ExtractionStatus.record(target, ExtractionStatus.STATUS_FAILED,
                result.getMessage());
            return false;
        }
        // The gate must never guess: a pick less sure than the schema asks, or one naming no category the question
        // offers, is left for the submitter to make
        final Map<String, FieldResult> sure = result.fields().entrySet().stream()
            .filter(entry -> isSure(entry.getValue(), thresholds.get(entry.getKey())))
            .filter(entry -> isOffered(entry.getValue(), questions.get(entry.getKey())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        // Only a sure pick is recorded, so an unsure one leaves the question open for the submitter and the next
        // reading. A pick recorded once is not asked again, even of a newer version.
        ExtractionFields.write(context.getResourceResolver(), target, submission, questions, sure, List.of(file));
        LOGGER.info("Reading step done: step=classify submission={} decided={} of={} ms={}", target.getPath(),
            sure.size(), fields.size(), (System.nanoTime() - startedAt) / 1_000_000L);
        return true;
    }

    /** Whether the model found a pick at least as sure as its classification asks. */
    private static boolean isSure(final FieldResult result, final Double threshold)
    {
        return result.found() && threshold != null && result.confidence() >= threshold;
    }

    /**
     * Whether every value of a pick is one of the categories its question offers. Not every provider holds the
     * reply to the schema's list, and a pick outside it would count as an answer that matches no condition.
     */
    private static boolean isOffered(final FieldResult result, final Question question)
    {
        final Set<String> offered = question.getOfferedOptions().stream()
            .map(OfferedOption::value)
            .collect(Collectors.toSet());
        return offered.containsAll(result.values());
    }

    /**
     * Finds the classification requirements whose question has no answer yet, grouped by document. A classification
     * whose condition does not hold, that holds no question, or whose question is already answered is left out.
     */
    private static Map<String, List<ClassificationRequirement>> getOpen(final Submission submission)
    {
        final Set<String> answered = ExtractionFields.getAnsweredOrSuggested(submission);
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
     * shown them after the document and before the questions.
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
                // Broken schema content: the classification goes on without it, so nobody else would notice
                ErrorLogger.logError(e, ErrorContext.of(ClassifyDocumentHandler.class, "readTemplate")
                    .about(classification.getPath()));
            }
        }
        return templates.isEmpty() ? null : templates.toString().strip();
    }

    /**
     * Ask the model, degraded with the reason when it could not be asked.
     */
    private IntakeResult ask(final File file, final ExtractionPrompt.Group classifications, final String templates)
    {
        final DocumentScan scan;
        try {
            scan = this.documents.scan(file);
        } catch (final IOException e) {
            LOGGER.warn("The text of {} could not be read: {}", file.getPath(), e.getMessage());
            // A repository fault: the submitter is told the text could not be read, not why
            ErrorLogger.logError(e, ErrorContext.of(ClassifyDocumentHandler.class, "readText").about(file.getPath()));
            return IntakeResult.createNotAsked(IntakeResult.TEXT_UNREADABLE);
        }
        return this.intake.run(file, scan, List.of(classifications), templates);
    }
}
