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

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.extraction.internal.FormsToRead.FormToRead;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Asks the model one form's open questions about the documents the form names: their parsed text, the form's
 * template as reference text, and the questions, at most ten per call.
 *
 * <p>One document is sent as parsed, so the call starts the same way as the validation and categorisation of that
 * document did, and reuses their prompt cache. Several are joined into one text, each under its document's label,
 * and each quote is checked against the document it came from.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = FormReader.class)
public class FormReader
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FormReader.class);

    @Reference
    private AnswerIntakeService intake;

    @Reference
    private ParsedDocuments documents;

    @Reference
    private ReadingRuns runs;

    /**
     * Asks the model the form's open questions.
     *
     * @param submission the submission being read, so a Stop knows what is read
     * @param reading the form, its documents and its open questions
     * @return the model's answers, or why it could not be asked
     */
    IntakeResult ask(final Submission submission, final FormToRead reading)
    {
        this.runs.setFiles(submission.getPath(),
            reading.files().stream().map(File::getPath).collect(Collectors.toSet()));
        final DocumentScan scan;
        try {
            scan = getScan(reading);
        } catch (final IOException e) {
            LOGGER.warn("The text of {} could not be read: {}", reading.form().getPath(), e.getMessage());
            // A repository fault: the submitter is told the text could not be read, not why
            ErrorLogger.logError(e, ErrorContext.of(FormReader.class, "readText").about(submission.getPath())
                .with("form", reading.form().getPath()));
            return IntakeResult.createNotRead(IntakeResult.TEXT_UNREADABLE_MESSAGE);
        }
        return this.intake.askQuestions(reading.files().get(0), scan,
            List.of(new ExtractionPrompt.Group(reading.form().getName(),
                ExtractionFields.toFields(reading.questions()))),
            ReferenceTemplates.readTemplate(reading.form(), reading.form().getTemplate(), FormReader.class));
    }

    /** The text sent to the model: one document as parsed, or several joined, each under its document's label. */
    private DocumentScan getScan(final FormToRead reading) throws IOException
    {
        if (reading.files().size() == 1) {
            return this.documents.scan(reading.files().get(0));
        }
        // Read the raw text, not each document's scan: the joined text is scanned once anyway
        final List<String> texts = new ArrayList<>();
        for (final File file : reading.files()) {
            texts.add(DocumentText.readText(file.getMarkdownFile()));
        }
        return DocumentScan.join(reading.labels(), texts);
    }
}
