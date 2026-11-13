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
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.extraction.internal.FormsToRead.FormToRead;
import io.uhndata.iap.schemas.models.DecisionRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.File;

/**
 * Asks the model a requirement's questions about the documents it reads: a validation's verdict, a
 * classification's pick, or a form's open questions. The model is sent the documents' parsed text, the
 * requirement's template as reference text, and the questions.
 *
 * <p>One document is sent as parsed, so the call shares the prompt cache of the document's other calls
 * ({@link ExtractionPrompt}). Several are joined into one text, each under its document's label, and each quote is
 * checked against the document it came from.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = RequirementReader.class)
public class RequirementReader
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RequirementReader.class);

    @Reference
    private AnswerIntakeService intake;

    @Reference
    private ParsedDocuments documents;

    @Reference
    private ReadingRuns runs;

    /**
     * What to ask the model.
     *
     * @param requirement the requirement the questions belong to, which names the reference text
     * @param template the requirement's template, or {@code null} when it has none
     * @param group what the questions are asked under
     * @param files the parsed uploads to read, at least one
     * @param labels the label of each upload's document requirement, in the same order; only a joined text uses them
     * @param fields the questions
     * @version $Id$
     * @since 0.1.0
     */
    record Request(Requirement requirement, Resource template, String group, List<File> files, List<String> labels,
        List<ExtractionField> fields)
    {
        /**
         * Asks one decision about one upload, such as a validation.
         *
         * @param decision the decision
         * @param file the parsed upload it reads
         * @param field what the model answers
         * @return the request
         */
        static Request of(final DecisionRequirement decision, final File file, final ExtractionField field)
        {
            return new Request(decision, decision.getTemplate(), decision.getDocumentRequirementName(), List.of(file),
                List.of(), List.of(field));
        }

        /**
         * Asks a form's open questions about the documents it names.
         *
         * @param reading the form, its documents and its open questions
         * @return the request
         */
        static Request of(final FormToRead reading)
        {
            return new Request(reading.form(), reading.form().getTemplate(), reading.form().getName(), reading.files(),
                reading.labels(), ExtractionFields.toFields(reading.questions()));
        }
    }

    /**
     * Asks the model the request's questions.
     *
     * @param submission the path of the submission being read, so a Stop knows which files are read
     * @param request what to ask
     * @return the model's answers, or why it could not be asked
     */
    IntakeResult ask(final String submission, final Request request)
    {
        this.runs.setFiles(submission, request.files().stream().map(File::getPath).collect(Collectors.toSet()));
        final DocumentScan scan;
        try {
            scan = getScan(request);
        } catch (final IOException e) {
            LOGGER.warn("The documents {} reads could not be read: {}", request.requirement().getPath(),
                e.getMessage());
            // A repository fault: the submitter is told the text could not be read, not why
            ErrorLogger.logError(e, ErrorContext.of(RequirementReader.class, "readText").about(submission)
                .with("requirement", request.requirement().getPath()));
            return IntakeResult.createNotRead(IntakeResult.TEXT_UNREADABLE_MESSAGE);
        }
        return this.intake.askQuestions(request.files().get(0), scan,
            List.of(new ExtractionPrompt.Group(request.group(), request.fields())), readTemplate(request));
    }

    /** The text sent to the model: one document as parsed, or several joined, each under its document's label. */
    private DocumentScan getScan(final Request request) throws IOException
    {
        if (request.files().size() == 1) {
            return this.documents.scan(request.files().get(0));
        }
        // Read the raw text, not each document's scan: the joined text is scanned once anyway
        final List<String> texts = new ArrayList<>();
        for (final File file : request.files()) {
            texts.add(DocumentText.readText(file.getMarkdownFile()));
        }
        return DocumentScan.join(request.labels(), texts);
    }

    /**
     * The requirement's reference text, under a heading naming what it is for. The model is shown it after the
     * document and before the questions.
     *
     * @return the text, or {@code null} when there is no template or it cannot be read
     */
    private static String readTemplate(final Request request)
    {
        final Requirement requirement = request.requirement();
        if (request.template() == null) {
            return null;
        }
        try {
            return "# Reference for \"" + requirement.getLabel() + "\"\n\n"
                + DocumentText.readText(request.template()).strip();
        } catch (final IOException e) {
            // The model is still worth asking without the reference, which only sharpens its answers
            LOGGER.warn("The template of {} could not be read: {}", requirement.getPath(), e.getMessage());
            // Broken schema content: the reading goes on without it, so nobody else would notice
            ErrorLogger.logError(e,
                ErrorContext.of(RequirementReader.class, "readTemplate").about(requirement.getPath()));
            return null;
        }
    }
}
