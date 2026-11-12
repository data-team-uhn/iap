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
import java.util.List;
import java.util.Set;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.schemas.models.DecisionRequirement;
import io.uhndata.iap.submissions.models.File;

/**
 * Asks the model one decision about an uploaded document, such as a validation: the document's parsed text, the
 * decision's template as reference text, and one field to answer.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = DecisionReader.class)
public class DecisionReader
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionReader.class);

    @Reference
    private AnswerIntakeService intake;

    @Reference
    private ParsedDocuments documents;

    @Reference
    private ReadingRuns runs;

    /**
     * Asks the model the decision about the file.
     *
     * @param submission the path of the submission being read, so a Stop knows which file is being read
     * @param file the parsed upload the decision reads
     * @param requirement the decision, for its document's name and its template
     * @param field what the model answers
     * @return the model's answer, or why it could not be asked
     */
    IntakeResult ask(final String submission, final File file, final DecisionRequirement requirement,
        final ExtractionField field)
    {
        this.runs.setFiles(submission, Set.of(file.getPath()));
        final DocumentScan scan;
        try {
            scan = this.documents.scan(file);
        } catch (final IOException e) {
            LOGGER.warn("The text of {} could not be read: {}", file.getPath(), e.getMessage());
            // A repository fault: the submitter is told the text could not be read, not why
            ErrorLogger.logError(e, ErrorContext.of(DecisionReader.class, "readText").about(file.getPath()));
            return IntakeResult.createNotRead(IntakeResult.TEXT_UNREADABLE_MESSAGE);
        }
        return this.intake.askQuestions(file, scan,
            List.of(new ExtractionPrompt.Group(requirement.getDocumentRequirementName(), List.of(field))),
            DecisionTemplates.readTemplate(requirement, DecisionReader.class));
    }
}
