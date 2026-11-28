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
import java.util.stream.Stream;

import jakarta.json.JsonObjectBuilder;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.RequirementDescriber;

/**
 * Warns the submitter, on a document requirement, when its upload is too long for the model to read.
 *
 * <p>Worked out each time the form is read, for the model active then: every check and every form with open
 * questions that reads the newest upload is sized as its reading would be, with its own questions, its template
 * and the other documents it joins. A document nothing reads gets no warning. The reading measures the text itself
 * and decides; this only tells the submitter early, by the sizes the parse recorded.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = RequirementDescriber.class)
public class ReadingSizeDescriber implements RequirementDescriber
{
    /** The key of the warning in the requirement's object. */
    static final String READING_NOTICE_KEY = "readingNotice";

    /** What the submitter is told about an upload too long for one request to the model. */
    static final String TOO_LONG_NOTICE =
        "This document is too long for the model to read. Nothing will be suggested from it.";

    @Reference
    private FormsToRead formsToRead;

    @Reference
    private RequirementReader reader;

    @Reference
    private LLMConfigurationService configurationService;

    @Override
    public boolean handles(final Requirement requirement)
    {
        return requirement instanceof DocumentRequirement;
    }

    @Override
    public void describe(final Requirement requirement, final Submission submission, final JsonObjectBuilder json)
    {
        final File file = SubmissionFiles.findCurrentFile(submission, requirement.getName());
        if (file == null || file.getTokens() == null) {
            return;
        }
        final LLMSettings settings;
        try {
            settings = this.configurationService.getActiveSettings();
        } catch (final IOException e) {
            // The reading reports settings it cannot read; the form only goes without the warning
            return;
        }
        if (getReadings(submission, requirement.getName(), file)
            .anyMatch(request -> this.reader.isTooLong(request, settings))) {
            json.add(READING_NOTICE_KEY, TOO_LONG_NOTICE);
        }
    }

    /** What would be asked of the upload: each check that applies to it, and each form with open questions on it. */
    private Stream<RequirementReader.Request> getReadings(final Submission submission, final String document,
        final File file)
    {
        final Stream<RequirementReader.Request> checks = submission.getSchemaVersion().getRequirements().stream()
            .filter(submission::isApplicable)
            .flatMap(requirement -> {
                if (requirement instanceof final ValidationRequirement validation
                    && document.equals(validation.getDocumentRequirementName())) {
                    return Stream.of(RequirementReader.Request.ofValidation(validation, file));
                }
                if (requirement instanceof final ClassificationRequirement classification
                    && document.equals(classification.getDocumentRequirementName())) {
                    return Stream.of(RequirementReader.Request.ofClassification(submission, classification, file));
                }
                return Stream.empty();
            });
        final Stream<RequirementReader.Request> forms = this.formsToRead.getFormsToRead(submission).stream()
            .filter(form -> form.files().stream().anyMatch(read -> read.getPath().equals(file.getPath())))
            .map(RequirementReader.Request::of);
        return Stream.concat(checks, forms);
    }
}
