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
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that reads every extractable answer out of a proposal and records it: a {@code sub:Answer}
 * holding the value, with a {@code sub:Extraction} under it saying where it came from and how sure the model was,
 * and a {@code sub:Evidence} for each quote. A question that already carries an answer is left alone, whoever
 * or whatever wrote it: reading again fills the gaps, it never replaces an answer that is already there.
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

    /**
     * The activity property naming the requirement to ask about. Absent means the whole schema version, which
     * is one call for everything; naming one asks only what that requirement holds, so a schema can be read in
     * stages and each stage is a step on the diagram.
     */
    static final String REQUIREMENT = "requirement";

    /**
     * The activity property naming the document requirements to read, in the order they are sent. Each one's
     * parsed text goes under a heading of its own, and together they are one document to the model. Absent
     * means the first parsed document on the submission.
     */
    static final String DOCUMENTS = "documents";

    /**
     * The activity property naming extra system-prompt files for this step only, from the bundle's
     * {@code /prompts} folder. A string or a list; each file is prepended to the intake prompt so domain
     * knowledge that is only worth sending for one schema is not added to every reading.
     */
    static final String PROMPT_FROM = "promptFrom";

    /**
     * The activity property that decides whether this step writes what the model found. {@code path=value},
     * for example {@code common/isProposal=proposal}. The model is still asked every question; if that field
     * did not come back as the value, nothing is recorded and the reading is marked done.
     */
    static final String RECORD_WHEN = "recordWhen";

    private static final Logger LOGGER = LoggerFactory.getLogger(IntakeAnswersHandler.class);

    @Reference
    private AnswerIntakeService intake;

    @Reference
    private ParsedDocuments documents;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws PersistenceException
    {
        final Resource target = context.getTarget();
        final Submission submission = SubmissionFiles.submission(target);
        final List<Part> parts = partsToRead(context, submission);
        if (parts == null) {
            return;
        }
        final String requirement = context.getActivity().get(REQUIREMENT, String.class);
        final Map<String, Question> questions = ExtractionFields.extractable(submission, requirement);
        if (questions.isEmpty()) {
            // A named requirement that asks nothing is a step whose turn did not come - the condition that
            // would have brought it in did not hold - and saying the schema asks nothing would be wrong.
            if (requirement == null || requirement.isBlank()) {
                ExtractionStatus.record(target, ExtractionStatus.DONE, "The schema asks nothing of the document");
            }
            return;
        }
        final long startedAt = System.nanoTime();
        LOGGER.info("Reading step started: step=intake submission={} requirement={} questions={}",
            target.getPath(), requirement, questions.size());
        final IntakeResult result = ask(parts, questions, extraSystem(context));
        if (result == null || result.degraded()) {
            ExtractionStatus.record(target, ExtractionStatus.FAILED, "The model's answer could not be read");
            return;
        }
        if (!shouldRecord(context.getActivity().get(RECORD_WHEN, String.class), result.fields())) {
            ExtractionStatus.record(target, ExtractionStatus.DONE, null);
            LOGGER.info("Reading step done: step=intake submission={} recorded=0 of={} ms={}",
                target.getPath(), questions.size(), (System.nanoTime() - startedAt) / 1_000_000L);
            return;
        }
        final List<File> files = parts.stream().map(Part::file).toList();
        ExtractionFields.write(context.getResourceResolver(), target, submission, questions, result.fields(),
            files);
        ExtractionStatus.record(target, ExtractionStatus.DONE, null);
        LOGGER.info("Reading step done: step=intake submission={} answered={} of={} ms={}", target.getPath(),
            result.fields().values().stream().filter(FieldResult::found).count(), questions.size(),
            (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * What this step reads, or {@code null} when there is nothing to read and the reason has been recorded.
     *
     * <p>A step that names its documents reads each of them that has been parsed, in the order named. One that
     * was never uploaded is skipped. One whose parse failed fails the step, so the person is told why instead
     * of a reading marked done on whatever else parsed. One that names none reads the first parsed document
     * on the submission.</p>
     *
     * @param context the execution
     * @param submission the submission being read
     * @return the parts to send, or {@code null} when there are none
     * @throws PersistenceException if the reason cannot be recorded
     */
    private static List<Part> partsToRead(final WorkflowTaskContext context, final Submission submission)
        throws PersistenceException
    {
        final String[] named = context.getActivity().get(DOCUMENTS, String[].class);
        if (named == null || named.length == 0) {
            final File file = documentToRead(context, submission);
            return file == null ? null : List.of(new Part(file, null));
        }
        final List<Part> parts = new ArrayList<>();
        for (final String requirement : named) {
            final File file = SubmissionFiles.currentFor(submission, requirement);
            if (file == null) {
                continue;
            }
            if (ParsePropertyNames.STATUS_FAILED.equals(file.getParseStatus())) {
                ExtractionStatus.record(context.getTarget(), ExtractionStatus.FAILED,
                    SubmissionFiles.whyUnreadable(file));
                return null;
            }
            if (ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus())) {
                parts.add(new Part(file, getHeading(requirement)));
            }
        }
        if (parts.isEmpty()) {
            ExtractionStatus.record(context.getTarget(), ExtractionStatus.FAILED,
                SubmissionFiles.whyNothingToRead(submission));
            return null;
        }
        return parts;
    }

    /** The heading a document is sent under: its requirement's name, capitalized, as in "# Preamble". */
    private static String getHeading(final String requirement)
    {
        return Character.toUpperCase(requirement.charAt(0)) + requirement.substring(1);
    }

    /**
     * The document this step reads, or {@code null} when there is nothing to read and the reason has been
     * recorded. The first parsed file on the submission: a later gateway, not this step, decides whether
     * there is anything left to ask.
     *
     * @param context the execution
     * @param submission the submission being read
     * @return the file to read, or {@code null} when there is none
     * @throws PersistenceException if the reason cannot be recorded
     */
    private static File documentToRead(final WorkflowTaskContext context, final Submission submission)
        throws PersistenceException
    {
        final File parsed = SubmissionFiles.firstParsed(submission);
        if (parsed == null) {
            ExtractionStatus.record(context.getTarget(), ExtractionStatus.FAILED,
                SubmissionFiles.whyNothingToRead(submission));
        }
        return parsed;
    }

    /**
     * Whether the model's answers should be written. No gate means yes. A gate that does not hold, or that
     * cannot be read, means write nothing: a document that is not a proposal must not leave other facts behind.
     */
    static boolean shouldRecord(final String gate, final Map<String, FieldResult> fields)
    {
        if (gate == null || gate.isBlank()) {
            return true;
        }
        final int split = gate.indexOf('=');
        if (split <= 0 || split == gate.length() - 1) {
            return false;
        }
        final String name = gate.substring(0, split).strip();
        final String expected = gate.substring(split + 1).strip();
        if (name.isEmpty() || expected.isEmpty()) {
            return false;
        }
        final FieldResult field = fields.get(name);
        return field != null && field.found() && expected.equals(field.value());
    }

    /**
     * Extra system instructions this step names, concatenated in the order named. A single name reads as a
     * list of one. Blank names add nothing.
     */
    static String extraSystem(final WorkflowTaskContext context)
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
     * Put the questions to the model and write down the answers it read.
     *
     * @return what the model read out, {@code null} when it could not be asked at all
     */
    private IntakeResult ask(final List<Part> parts, final Map<String, Question> questions,
        final String extraSystem)
    {
        final File first = parts.get(0).file();
        try {
            return this.intake.run(first, scanOf(parts), ExtractionFields.toFields(questions), extraSystem);
        } catch (final IOException e) {
            // Not a failure of the walk. Throwing here reverts everything the reading has written so far and
            // leaves the committed `running` behind, with no step left to move it on
            LOGGER.warn("The intake could not be asked about {}: {}", first.getPath(), e.getMessage());
            return null;
        }
    }

    /**
     * The text sent to the model: one document as parsed, or several each under its own heading.
     *
     * <p>Quotes are checked against this same text, so a passage quoted from either document is found, and the
     * joined text remembers where each document starts, so each passage is stored with the one it came from.</p>
     */
    private DocumentScan scanOf(final List<Part> parts) throws IOException
    {
        if (parts.size() == 1 && parts.get(0).heading() == null) {
            return this.documents.scan(parts.get(0).file());
        }
        final List<String> headings = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        for (final Part part : parts) {
            headings.add(part.heading());
            texts.add(this.documents.scan(part.file()).getText());
        }
        return DocumentScan.joined(headings, texts);
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
