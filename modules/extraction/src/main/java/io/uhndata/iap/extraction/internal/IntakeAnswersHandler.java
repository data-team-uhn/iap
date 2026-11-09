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
import io.uhndata.iap.schemas.models.FormRequirement;
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
 * <p>A step asks only the questions of the requirements it names. Reading the same document version again asks
 * every one of them the submitter has not answered, suggested or not; the newest run is the one shown. The first
 * reading of a newer document version asks every question again, answered or not. Its runs are new suggestions;
 * they never change an answer. One is shown beside an answer only when it differs from both the answer and the
 * run before ({@code Answer.getOfferedSuggestion}).</p>
 *
 * <p>Questions are keyed by their path within the schema version, with dots for slashes, so two sections can both
 * have a {@code title}.</p>
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
     * The activity property naming the requirement to ask about, or a list of them, each asked as its own group.
     * Required: a step asks only the questions that belong to it, so a schema is read in stages, one step per
     * stage.
     */
    static final String REQUIREMENT_PARAMETER = "requirement";

    /** What the submitter is told when a step names no requirement, which is a mistake in the workflow. */
    static final String SET_UP_WRONG_MESSAGE = "This reading is not set up correctly";

    /**
     * The activity property naming the document requirements to read, in the order they are sent. Each parsed
     * document goes under its own heading, and the model sees them as one text. Absent means the first parsed
     * document on the submission.
     */
    static final String DOCUMENTS_PARAMETER = "documents";

    /**
     * The activity property naming reference files for this step only, from the bundle's {@code /prompts}
     * folder. A string or a list. They are sent after the document as reference material, which helps the model
     * understand the questions; they are not instructions it must follow.
     */
    static final String REFERENCES_PARAMETER = "promptFrom";

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
     * The whole intake step: pick the documents and the questions, ask the model, and save its answers.
     *
     * <ol>
     * <li>The step names no requirement, or one that is not a form of the schema: record that the reading is set
     * up wrong, and stop.</li>
     * <li>Find the parsed documents this step reads. None: record why and stop.</li>
     * <li>Pick the questions of the named requirements. Drop the ones the submitter answered, unless they are not
     * read from a newer version yet. None left: stop.</li>
     * <li>Ask the model, one requirement after another, at most ten questions per call. No call went through:
     * record why and stop.</li>
     * <li>Save one new run under each question whose call went through, found or not. When some calls did not
     * go through, leave a note saying so. The status is left to {@code finishReading}, which ends the reading.</li>
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
        final List<String> requirements = getRequirementNames(context);
        if (requirements.isEmpty()) {
            recordSetUpWrong(context, target, "names no requirement", "");
            return;
        }
        final List<String> unknown = getUnknownRequirements(submission, requirements);
        if (!unknown.isEmpty()) {
            recordSetUpWrong(context, target, "names a requirement that is not a form of the schema",
                String.join(",", unknown));
            return;
        }
        final List<Part> parts = getPartsToRead(context, submission);
        if (parts == null) {
            return;
        }
        final List<File> files = parts.stream().map(Part::file).toList();
        final Map<String, Map<String, Question>> grouped =
            getQuestionsToAsk(submission, getQuestionsByRequirement(submission, requirements), files);
        final Map<String, Question> questions = new LinkedHashMap<>();
        grouped.values().forEach(questions::putAll);
        if (questions.isEmpty()) {
            // Everything it asks is answered already, or its requirements do not apply here: nothing to do. The
            // reading's last step, finishReading, ends it.
            return;
        }
        this.runs.setFiles(target.getPath(), files.stream().map(File::getPath).collect(Collectors.toSet()));
        final long startedAt = System.nanoTime();
        LOGGER.info("Reading step started: step=intake submission={} requirement={} questions={}",
            target.getPath(), String.join(",", requirements), questions.size());
        final IntakeResult result = ask(parts, grouped, getReferences(context));
        if (result.status() == AnswerIntakeService.Status.NOT_READ) {
            ExtractionStatus.recordStatus(target, ExtractionStatus.STATUS_FAILED, result.message());
            return;
        }
        ExtractionFields.write(context.getResourceResolver(), target, submission, questions, result.fields(),
            files);
        if (result.status() == AnswerIntakeService.Status.INCOMPLETE) {
            // Kept by finishReading when it ends the reading, so the submitter knows Read again is worth it
            ExtractionStatus.recordNote(target, result.message());
        }
        LOGGER.info("Reading step done: step=intake submission={} answered={} of={} ms={}", target.getPath(),
            result.fields().values().stream().filter(FieldResult::isFound).count(), questions.size(),
            (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * Fails a step whose requirements are named wrong. A step asks only its own questions, so a mistake there is in
     * the workflow, which an administrator has to fix.
     *
     * @param context the step
     * @param target the submission's resource
     * @param problem what is wrong with the step, such as "names no requirement"
     * @param names the names at fault, empty when there are none
     * @throws PersistenceException if the status cannot be written
     */
    private static void recordSetUpWrong(final WorkflowTaskContext context, final Resource target,
        final String problem, final String names) throws PersistenceException
    {
        final String step = context.getActivity().getPath();
        LOGGER.warn("The intake step {} {} {}", step, problem, names);
        ErrorLogger.logProblem("The intake step " + step + " " + problem,
            ErrorContext.of(IntakeAnswersHandler.class, REQUIREMENT_PARAMETER).about(target.getPath())
                .with(REQUIREMENT_PARAMETER, names));
        ExtractionStatus.recordStatus(target, ExtractionStatus.STATUS_FAILED, SET_UP_WRONG_MESSAGE);
    }

    /**
     * The named requirements that are not a form requirement of the submission's schema version: a typo, or the
     * name of a document requirement. A form that does not apply to this submission is not one of them.
     *
     * @param submission the submission
     * @param requirements the requirement names, or their full paths
     * @return the names that match no form requirement, in the order named
     */
    private static List<String> getUnknownRequirements(final Submission submission, final List<String> requirements)
    {
        final List<FormRequirement> forms = submission.getSchemaVersion().getFormRequirements();
        return requirements.stream()
            .filter(name -> forms.stream().noneMatch(form -> name.equals(form.getName())
                || name.equals(form.getPath())))
            .toList();
    }

    /**
     * The questions this step asks, by requirement. A question the submitter answered is left out, unless it is
     * not yet read from a newer version of a document the step reads.
     *
     * @param submission the submission
     * @param byRequirement every question the step names, answered or not
     * @param files the files the step reads
     * @return the questions to ask, by requirement; a requirement with none left is left out
     */
    private static Map<String, Map<String, Question>> getQuestionsToAsk(final Submission submission,
        final Map<String, Map<String, Question>> byRequirement, final List<File> files)
    {
        final Set<String> answered = ExtractionFields.getAnswered(submission);
        final Set<String> unread = ExtractionFields.getUnreadInNewerVersions(submission,
            byRequirement.values().stream().flatMap(group -> group.values().stream()).toList(), files);
        final Map<String, Map<String, Question>> toAsk = new LinkedHashMap<>();
        byRequirement.forEach((name, group) -> {
            final Map<String, Question> kept = new LinkedHashMap<>(group);
            kept.values().removeIf(question -> answered.contains(question.getPath())
                && !unread.contains(question.getPath()));
            if (!kept.isEmpty()) {
                toAsk.put(name, kept);
            }
        });
        return toAsk;
    }

    /**
     * The names of the requirements this step asks, trimmed, in the order named.
     *
     * @param context the step
     * @return the names, empty when the step names none
     */
    private static List<String> getRequirementNames(final WorkflowTaskContext context)
    {
        final String[] named = context.getActivity().get(REQUIREMENT_PARAMETER, String[].class);
        final List<String> names = new ArrayList<>();
        for (final String value : named == null ? new String[0] : named) {
            // A diagram writes a list as one comma-separated value, so split on commas
            for (final String name : value.split(",")) {
                if (!name.isBlank()) {
                    names.add(name.strip());
                }
            }
        }
        return names;
    }

    /**
     * Every extractable question the step names, answered or not, grouped by requirement in the order named. Each
     * group is asked in calls of its own.
     *
     * @param submission the submission
     * @param requirements the requirement names
     * @return the questions of each requirement, keyed by question key; a requirement with none is left out
     */
    private static Map<String, Map<String, Question>> getQuestionsByRequirement(final Submission submission,
        final List<String> requirements)
    {
        final Map<String, Map<String, Question>> grouped = new LinkedHashMap<>();
        for (final String name : requirements) {
            final Map<String, Question> questions = ExtractionFields.getExtractable(submission, name);
            if (!questions.isEmpty()) {
                grouped.put(name, questions);
            }
        }
        return grouped;
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
                ExtractionStatus.recordStatus(context.getTarget(), ExtractionStatus.STATUS_FAILED,
                    SubmissionFiles.describeUnreadable());
                return null;
            }
            if (ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus())) {
                parts.add(new Part(file, getHeading(requirement)));
            }
        }
        if (parts.isEmpty()) {
            ExtractionStatus.recordStatus(context.getTarget(), ExtractionStatus.STATUS_FAILED,
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
            ExtractionStatus.recordStatus(context.getTarget(), ExtractionStatus.STATUS_FAILED,
                SubmissionFiles.describeNothingToRead(submission));
        }
        return parsed;
    }

    /**
     * The reference text this step names in {@code promptFrom}, joined in the order named, or {@code null} when
     * there is none. Blank names are skipped. It is sent after the document, so it does not change the start every
     * call on the document shares.
     */
    static String getReferences(final WorkflowTaskContext context)
    {
        final String[] named = context.getActivity().get(REFERENCES_PARAMETER, String[].class);
        final StringBuilder references = new StringBuilder();
        for (final String name : named == null ? new String[0] : named) {
            if (!name.isBlank()) {
                references.append(Prompts.read(name)).append("\n\n");
            }
        }
        return references.isEmpty() ? null : references.toString().strip();
    }

    /**
     * Asks the model the questions about the documents. A failure comes back as a degraded result, not an
     * exception.
     *
     * @param parts the documents to send
     * @param grouped the questions to ask, by requirement
     * @param references reference text for the model, or {@code null}
     * @return what the model read, or a degraded result with the reason when it could not be asked
     */
    private IntakeResult ask(final List<Part> parts, final Map<String, Map<String, Question>> grouped,
        final String references)
    {
        final File first = parts.get(0).file();
        final DocumentScan scan;
        try {
            scan = getScan(parts);
        } catch (final IOException e) {
            LOGGER.warn("The text of {} could not be read: {}", first.getPath(), e.getMessage());
            // A repository fault: the submitter only sees a generic message, so log it for an administrator
            ErrorLogger.logError(e, ErrorContext.of(IntakeAnswersHandler.class, "readText").about(first.getPath()));
            return IntakeResult.createNotRead(IntakeResult.TEXT_UNREADABLE_MESSAGE);
        }
        final List<ExtractionPrompt.Group> groups = grouped.entrySet().stream()
            .map(group -> new ExtractionPrompt.Group(group.getKey(), ExtractionFields.toFields(group.getValue())))
            .toList();
        return this.intake.askQuestions(first, scan, groups, references);
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
            texts.add(DocumentText.readText(part.file().getMarkdownFile()));
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
