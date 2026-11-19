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
import java.util.function.Predicate;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;

import io.uhndata.iap.schemas.models.DecisionRequirement;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.models.Validation;

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

    /** The id of the job that took the reading, set once the parse was read in. */
    private static final String CLAIMED_PROPERTY = "extractionReadingClaimedBy";

    private ReadingProjection()
    {
        // Static helper
    }

    /**
     * Whether a model reads this submission's uploads: a requirement that applies checks them with a validation or
     * a classification, or is a form that names the documents it is read from. Asked per submission, since a
     * schema may read documents on one route and not on another.
     *
     * @param version the submission's schema version
     * @param applies whether a requirement applies to the submission
     * @return {@code true} when uploads are read for answers
     */
    static boolean isReadingDocuments(final SchemaVersion version, final Predicate<Requirement> applies)
    {
        return version.getRequirements().stream().filter(applies).anyMatch(requirement ->
            requirement instanceof DecisionRequirement
                || requirement instanceof final FormRequirement form && !form.getReadDocumentNames().isEmpty());
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
            .add("reading", submission.get(CLAIMED_PROPERTY, String.class) != null)
            .add("blocked", isRejected(submission));
        Optional.ofNullable(submission.get(MESSAGE_PROPERTY, String.class))
            .ifPresent(message -> json.add("message", message));
        Optional.ofNullable(ParseStatus.findParseError(submission))
            .ifPresent(detail -> json.add("detail", detail));
        return Optional.of(json);
    }

    /**
     * Whether a validation that applies is sure the upload is not what the request needs. The same document would
     * get the same answer, so the request cannot go on with it.
     */
    private static boolean isRejected(final Submission submission)
    {
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(ValidationRequirement.class::isInstance)
            .map(ValidationRequirement.class::cast)
            .filter(submission::isApplicable)
            .map(submission::findValidation)
            .anyMatch(verdict -> verdict != null && Validation.OUTCOME_NO.equals(verdict.getOutcome()));
    }
}
