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

import java.util.Comparator;
import java.util.List;

import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.DecisionRequirement;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.models.Validation;

/**
 * The decisions {@link DecisionJobConsumer} makes about a submission's uploads, in the order it makes them: for
 * each document, in the order of the schema's document requirements, its validation, then its classification.
 * Only decisions that apply and name one of the schema's documents count. A classification that holds no question
 * is left out, since there is nothing to save its pick under.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DecisionChain
{
    private DecisionChain()
    {
        // Utility
    }

    /**
     * Whether nothing is left to decide: a validation said no, which ends the reading, or every decision whose
     * document was uploaded is made.
     *
     * @param submission the submission
     * @return {@code true} when the model has nothing left to be asked
     */
    static boolean isFinished(final Submission submission)
    {
        final List<DecisionRequirement> uploaded = getUploadedDecisions(submission);
        return !uploaded.isEmpty() && (uploaded.stream().anyMatch(decision -> isRejected(submission, decision))
            || uploaded.stream().allMatch(decision -> isMade(submission, decision)));
    }

    /**
     * Why the chain cannot start: the schema has no validation or classification, a document still to be checked
     * is missing, or none of their documents was uploaded.
     *
     * @param submission the submission
     * @return the message for the submitter, or {@code null} when every document still to be checked is parsed
     */
    static String findReasonNotToStart(final Submission submission)
    {
        final List<DecisionRequirement> decisions = getDecisions(submission);
        if (decisions.isEmpty()) {
            return ReadingJobs.NOT_SET_UP_MESSAGE;
        }
        final String missing = decisions.stream()
            .filter(decision -> !isMade(submission, decision) && isMissing(submission, decision))
            .map(decision -> describeMissing(getDocument(submission, decision).getLabel()))
            .findFirst()
            .orElse(null);
        if (missing != null) {
            return missing;
        }
        return getPendingDecisions(submission).isEmpty() ? SubmissionFiles.NO_DOCUMENT_MESSAGE : null;
    }

    /**
     * What the submitter is told when a document to check is missing.
     *
     * @param label the document requirement's label
     * @return the message
     */
    static String describeMissing(final String label)
    {
        return "The " + label + " is missing, please upload it";
    }

    /**
     * The decisions whose document has an upload and that are not made yet, in chain order.
     *
     * @param submission the submission
     * @return the decisions still to make
     */
    static List<DecisionRequirement> getPendingDecisions(final Submission submission)
    {
        return getUploadedDecisions(submission).stream()
            .filter(decision -> !isMade(submission, decision))
            .toList();
    }

    /**
     * The latest upload of the document the decision reads.
     *
     * @param submission the submission
     * @param decision the decision
     * @return the upload, or {@code null} when the document was not uploaded
     */
    static File getFile(final Submission submission, final DecisionRequirement decision)
    {
        return SubmissionFiles.getCurrentFile(submission, decision.getDocumentRequirementName());
    }

    /** Every decision in chain order: by document, and within one document the validation first. */
    private static List<DecisionRequirement> getDecisions(final Submission submission)
    {
        final List<String> documents = submission.getSchemaVersion().getDocumentRequirements().stream()
            .map(DocumentRequirement::getName)
            .toList();
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(requirement -> requirement instanceof ValidationRequirement
                || requirement instanceof final ClassificationRequirement classification
                && classification.getQuestion() != null)
            .map(DecisionRequirement.class::cast)
            .filter(decision -> documents.contains(decision.getDocumentRequirementName())
                && submission.isApplicable(decision))
            .sorted(Comparator.<DecisionRequirement>comparingInt(
                decision -> documents.indexOf(decision.getDocumentRequirementName()))
                .thenComparing(decision -> decision instanceof ClassificationRequirement))
            .toList();
    }

    /** The decisions whose document has an upload, in chain order. */
    private static List<DecisionRequirement> getUploadedDecisions(final Submission submission)
    {
        return getDecisions(submission).stream()
            .filter(decision -> getFile(submission, decision) != null)
            .toList();
    }

    /**
     * Whether the decision is made: a validation has a verdict; a classification was read, or the submitter
     * already answered its question.
     */
    private static boolean isMade(final Submission submission, final DecisionRequirement decision)
    {
        if (decision instanceof final ValidationRequirement validation) {
            return getVerdict(submission, validation) != null;
        }
        return ExtractionFields.isAnsweredOrRead(submission,
            ((ClassificationRequirement) decision).getQuestion());
    }

    /** Whether the decision is a validation that said no. */
    private static boolean isRejected(final Submission submission, final DecisionRequirement decision)
    {
        return decision instanceof final ValidationRequirement validation
            && Validation.OUTCOME_NO.equals(getVerdict(submission, validation));
    }

    /**
     * Whether a document a decision reads should be there and cannot be read: a required one was never uploaded,
     * which the submission view should have prevented, or its parse failed. An optional one left out is not missing.
     */
    private static boolean isMissing(final Submission submission, final DecisionRequirement decision)
    {
        final File file = getFile(submission, decision);
        if (file == null) {
            final DocumentRequirement document = getDocument(submission, decision);
            return document.isRequired() && submission.isApplicable(document);
        }
        // Every parse has settled by now, so one that is not completed failed or was stopped
        return !ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus());
    }

    /** The document requirement the decision reads; {@link #getDecisions} keeps only those that have one. */
    private static DocumentRequirement getDocument(final Submission submission, final DecisionRequirement decision)
    {
        return SubmissionFiles.findDocumentRequirement(submission, decision.getDocumentRequirementName());
    }

    /** The validation's {@code yes}, {@code no} or {@code unsure}, or {@code null} before the model was asked. */
    private static String getVerdict(final Submission submission, final ValidationRequirement requirement)
    {
        final Validation validation = submission.findValidation(requirement);
        return validation == null ? null : validation.getOutcome();
    }
}
