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
package io.uhndata.iap.submissions.internal;

import java.util.Optional;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;

import io.uhndata.iap.schemas.models.DecisionRequirement;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Submission;

/**
 * What the form says about reading the uploaded documents for answers: whether anything reads them, and where the
 * reading stands. The extraction module writes these properties; this only reads them back.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ReadingProjection
{
    private static final String STATUS_PROPERTY = "extractionStatus";

    private static final String MESSAGE_PROPERTY = "extractionMessage";

    /** Set by a reading that ended with the request unable to go on; the message says why. */
    private static final String BLOCKED_PROPERTY = "extractionBlocked";

    /** Set once a reading job has taken the reading, which is after the parse was read in. */
    private static final String CLAIMED_PROPERTY = "extractionReadingClaimed";

    private ReadingProjection()
    {
        // Static helper
    }

    /**
     * Whether a model reads the uploads: the schema version checks them with a validation or a classification, or
     * has a form that names the documents it is read from.
     *
     * @param version the schema version
     * @return {@code true} when uploads are read for answers
     */
    static boolean isReadingDocuments(final SchemaVersion version)
    {
        return version.getRequirements().stream().anyMatch(requirement -> requirement instanceof DecisionRequirement
            || requirement instanceof final FormRequirement form && !form.getDocumentRequirementNames().isEmpty());
    }

    /**
     * Where reading the documents stands, once it was ever asked for.
     *
     * @param submission the submission
     * @return the reading's JSON, empty when no reading was ever asked for
     */
    static Optional<JsonObjectBuilder> describe(final Submission submission)
    {
        final String status = submission.get(STATUS_PROPERTY, String.class);
        if (status == null) {
            return Optional.empty();
        }
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("status", status)
            .add("retryable", ParseStatus.hasFailedParse(submission))
            .add("parsed", ParseStatus.isParsed(submission))
            .add("reading", isSet(submission, CLAIMED_PROPERTY))
            .add("blocked", isSet(submission, BLOCKED_PROPERTY));
        Optional.ofNullable(submission.get(MESSAGE_PROPERTY, String.class))
            .ifPresent(message -> json.add("message", message));
        Optional.ofNullable(ParseStatus.findParseError(submission))
            .ifPresent(detail -> json.add("detail", detail));
        return Optional.of(json);
    }

    private static boolean isSet(final Submission submission, final String property)
    {
        return Boolean.TRUE.equals(submission.get(property, Boolean.class));
    }
}
