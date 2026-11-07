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
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that asks the model the schema's questions about a submission's documents and saves what it
 * reads. Each question asked gets a {@code sub:Extraction} (a suggestion, found or not) under its
 * {@code sub:Answer}, with a {@code sub:Context} for each quote. The answer's value is never written here.
 *
 * <p>Reading again only fills gaps: a question that already has an answer or a suggestion is skipped. The first
 * reading of a newer document version asks every question again. Its runs are new suggestions; they do not change
 * any answer.</p>
 *
 * <p>Questions are keyed by their path within the schema version, so two sections can both have a
 * {@code title}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class IntakeAnswersHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "intakeAnswers";

    /**
     * The activity property naming the requirement to ask about, or a list of them asked in one call. Absent
     * means every question of the schema version. Naming requirements lets a schema be read in stages, one step
     * per stage.
     */
    static final String REQUIREMENT_PARAMETER = "requirement";

    /**
     * The activity property naming the document requirements to read, in the order they are sent. Each parsed
     * document goes under its own heading, and the model sees them as one text. Absent means the first parsed
     * document on the submission.
     */
    static final String DOCUMENTS_PARAMETER = "documents";

    /**
     * The activity property naming extra system-prompt files for this step only, from the bundle's
     * {@code /prompts} folder. A string or a list. The files go before the intake prompt, so knowledge that only
     * one schema needs is not sent with every reading.
     */
    static final String PROMPT_FROM = "promptFrom";

    private static final Logger LOGGER = LoggerFactory.getLogger(IntakeAnswersHandler.class);

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
            read(context, target);
        } catch (final ReadingRuns.Stopped e) {
            Thread.interrupted();
            throw new PersistenceException(ExtractionStatus.STOPPED_MESSAGE, e);
        } finally {
            stopped = this.runs.end(target.getPath());
        }
        if (stopped) {
            // Stopped after the model answered: fail the step so nothing it wrote is kept
            throw new PersistenceException(ExtractionStatus.STOPPED_MESSAGE, new ReadingRuns.Stopped());
        }
    }

    /**
     * The whole intake step: pick the documents and the questions, ask the model once, and save its answers.
     *
     * <ol>
     * <li>Find the parsed documents this step reads. None: record why and stop.</li>
     * <li>Pick the questions this step asks. Unless the document is a newer version, drop the ones that already
     * have an answer or a suggestion. None left: stop.</li>
     * <li>Ask the model all of them in one call. The call failed: record why and stop.</li>
     * <li>Save one new run under each question asked, found or not.</li>
     * </ol>
     *
     * @param context the step
     * @param target the submission
     * @throws PersistenceException if a status cannot be written
     * @throws ReadingRuns.Stopped when the submitter stopped the reading during the model call
     */
    private void read(final WorkflowTaskContext context, final Resource target)
        throws PersistenceException, ReadingRuns.Stopped
    {
        final Submission submission = SubmissionFiles.getSubmission(target);
        final List<Part> parts = getPartsToRead(context, submission);
        if (parts == null) {
            return;
        }
        final String[] named = context.getActivity().get(REQUIREMENT_PARAMETER, String[].class);
        final String requirement = named == null ? null : String.join(",", named);
        final List<File> files = parts.stream().map(Part::file).toList();
        final Map<String, Question> questions = getQuestionsIncludingAnswered(submission, requirement);
        final boolean newRevision = ExtractionFields.isNewRevision(submission, questions.values(), files);
        if (!newRevision) {
            // Reading again only fills gaps; the first reading of a newer version asks every question
            final Set<String> answered = ExtractionFields.getAnswered(submission);
            questions.values().removeIf(question -> answered.contains(question.getPath()));
        }
        if (questions.isEmpty()) {
            recordNothingToAsk(target, submission, requirement);
            return;
        }
        this.runs.setFiles(target.getPath(), files.stream().map(File::getPath).collect(Collectors.toSet()));
        final long startedAt = System.nanoTime();
        LOGGER.info("Reading step started: step=intake submission={} requirement={} questions={}",
            target.getPath(), requirement, questions.size());
        final IntakeResult result = ask(parts, questions, getExtraSystem(context));
        if (result.degraded()) {
            ExtractionStatus.record(target, ExtractionStatus.STATUS_FAILED,
                result.getMessage());
            return;
        }
        ExtractionFields.write(context.getResourceResolver(), target, submission, questions, result.fields(),
            files);
        ExtractionStatus.recordDone(target, null);
        LOGGER.info("Reading step done: step=intake submission={} answered={} of={} ms={}", target.getPath(),
            result.fields().values().stream().filter(FieldResult::found).count(), questions.size(),
            (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * Records why a step asked the model nothing.
     *
     * @param target the submission's resource
     * @param submission the submission
     * @param requirement the requirements the step names, or {@code null}
     * @throws PersistenceException if the status cannot be written
     */
    private static void recordNothingToAsk(final Resource target, final Submission submission,
        final String requirement) throws PersistenceException
    {
        if (!getQuestionsIncludingAnswered(submission, requirement).isEmpty()) {
            // Everything it asks is answered already
            ExtractionStatus.recordDone(target, null);
        } else if (requirement == null || requirement.isBlank()) {
            // Only when no requirement is named: a named requirement with no questions just does not apply here
            ExtractionStatus.recordDone(target, "The schema asks nothing of the document");
        }
    }

    /** Every extractable question the step names, answered or not. Several requirements are joined in order. */
    private static Map<String, Question> getQuestionsIncludingAnswered(final Submission submission,
        final String requirement)
    {
        final Map<String, Question> questions = new LinkedHashMap<>();
        if (requirement == null || requirement.isBlank()) {
            questions.putAll(ExtractionFields.getExtractable(submission, null));
            return questions;
        }
        // A diagram writes a list as one comma-separated value, so split on commas
        for (final String name : requirement.split(",")) {
            if (!name.isBlank()) {
                questions.putAll(ExtractionFields.getExtractable(submission, name.trim()));
            }
        }
        return questions;
    }

    /**
     * The documents this step reads, or {@code null} when there is nothing to read. The reason is then recorded.
     *
     * <p>A step that names its documents reads each named one that has been parsed, in the order named. One with
     * no upload is skipped. One whose parse failed fails the whole step, so the submitter sees why instead of a
     * reading marked done on the others. A step that names none reads the first parsed document on the
     * submission.</p>
     *
     * @param context the execution
     * @param submission the submission being read
     * @return the documents to send, or {@code null} when there are none
     * @throws PersistenceException if the reason cannot be recorded
     */
    private static List<Part> getPartsToRead(final WorkflowTaskContext context, final Submission submission)
        throws PersistenceException
    {
        final String[] named = context.getActivity().get(DOCUMENTS_PARAMETER, String[].class);
        if (named == null || named.length == 0) {
            final File file = getDocumentToRead(context, submission);
            return file == null ? null : List.of(new Part(file, null));
        }
        final List<Part> parts = new ArrayList<>();
        for (final String requirement : named) {
            final File file = SubmissionFiles.getCurrentFile(submission, requirement);
            if (file == null) {
                continue;
            }
            if (ParsePropertyNames.STATUS_FAILED.equals(file.getParseStatus())) {
                ExtractionStatus.record(context.getTarget(), ExtractionStatus.STATUS_FAILED,
                    SubmissionFiles.describeUnreadable());
                return null;
            }
            if (ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus())) {
                parts.add(new Part(file, getHeading(requirement)));
            }
        }
        if (parts.isEmpty()) {
            ExtractionStatus.record(context.getTarget(), ExtractionStatus.STATUS_FAILED,
                SubmissionFiles.describeNothingToRead(submission));
            return null;
        }
        return parts;
    }

    /** The heading a document is sent under: its requirement's name with a capital first letter, like "Preamble". */
    private static String getHeading(final String requirement)
    {
        return Character.toUpperCase(requirement.charAt(0)) + requirement.substring(1);
    }

    /**
     * The first parsed document on the submission, for a step that names no documents. Returns {@code null}
     * when there is none, and records why.
     *
     * @param context the execution
     * @param submission the submission being read
     * @return the file to read, or {@code null} when there is none
     * @throws PersistenceException if the reason cannot be recorded
     */
    private static File getDocumentToRead(final WorkflowTaskContext context, final Submission submission)
        throws PersistenceException
    {
        final File parsed = SubmissionFiles.findFirstParsed(submission);
        if (parsed == null) {
            ExtractionStatus.record(context.getTarget(), ExtractionStatus.STATUS_FAILED,
                SubmissionFiles.describeNothingToRead(submission));
        }
        return parsed;
    }

    /**
     * The extra system instructions this step names, joined in the order named, or {@code null} when there are
     * none. Blank names are skipped.
     */
    static String getExtraSystem(final WorkflowTaskContext context)
    {
        final String[] named = context.getActivity().get(PROMPT_FROM, String[].class);
        final StringBuilder extra = new StringBuilder();
        for (final String name : named == null ? new String[0] : named) {
            if (!name.isBlank()) {
                extra.append(Prompts.read(name)).append("\n\n");
            }
        }
        return extra.isEmpty() ? null : extra.toString().strip();
    }

    /**
     * Asks the model the questions about the documents. A failure comes back as a degraded result, not an
     * exception.
     *
     * @param parts the documents to send
     * @param questions the questions to ask
     * @param extraSystem extra system instructions, or {@code null}
     * @return what the model read, or a degraded result with the reason when it could not be asked
     */
    private IntakeResult ask(final List<Part> parts, final Map<String, Question> questions,
        final String extraSystem)
    {
        final File first = parts.get(0).file();
        final DocumentScan scan;
        try {
            scan = getScan(parts);
        } catch (final IOException e) {
            LOGGER.warn("The text of {} could not be read: {}", first.getPath(), e.getMessage());
            // A repository fault: the submitter only sees a generic message, so log it for an administrator
            ErrorLogger.logError(e, ErrorContext.of(IntakeAnswersHandler.class, "readText").about(first.getPath()));
            return IntakeResult.createNotAsked(IntakeResult.TEXT_UNREADABLE);
        }
        try {
            return this.intake.run(first, scan, ExtractionFields.toFields(questions), extraSystem);
        } catch (final ReadingRuns.Stopped e) {
            throw e;
        } catch (final IOException e) {
            if (ReadingRuns.isStopped(e)) {
                throw new ReadingRuns.Stopped();
            }
            // Recorded, not thrown: throwing would undo what earlier steps wrote and hide this reason
            LOGGER.warn("The intake could not be asked about {}: {}", first.getPath(), e.getMessage());
            // The model is down, and an administrator has to see that to fix it
            ErrorLogger.logError(e, ErrorContext.of(IntakeAnswersHandler.class, "askModel").about(first.getPath()));
            return IntakeResult.createNotAsked(IntakeResult.UNREACHABLE_MESSAGE);
        }
    }

    /**
     * The text sent to the model: one document as parsed, or several joined, each under its own heading.
     *
     * <p>Quotes are checked against this same text. The joined text remembers where each document starts, so
     * each quote is stored with the document it came from.</p>
     */
    private DocumentScan getScan(final List<Part> parts) throws IOException
    {
        if (parts.size() == 1 && parts.get(0).heading() == null) {
            return this.documents.scan(parts.get(0).file());
        }
        // Read the raw text, not each part's scan: the joined text is scanned once anyway, and each part's scan
        // would push the other out of the one-document cache
        final List<String> headings = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        for (final Part part : parts) {
            headings.add(part.heading());
            texts.add(DocumentText.readText(part.file().getFileMarkdown()));
        }
        return DocumentScan.join(headings, texts);
    }

    /**
     * One document to send, and the heading it goes under when several are sent together.
     *
     * @param file the parsed file
     * @param heading the heading, or {@code null} when the document is sent alone as parsed
     * @version $Id$
     * @since 0.1.0
     */
    private record Part(File file, String heading)
    {
    }

}
