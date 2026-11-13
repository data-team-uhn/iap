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
import java.util.Objects;
import java.util.Set;

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
     * Why the chain cannot start: nothing in the schema reads the documents, a document still to be checked is
     * missing, or none of the checked documents was uploaded. A schema with no validation or classification, but
     * with a form that names its documents, starts and ends at once: nothing is checked, and the forms are read on
     * Next. So does a chain whose every decision is made, such as when a document is parsed again.
     *
     * @param submission the submission
     * @return the message for the submitter, or {@code null} when every document still to be checked is parsed
     */
    static String findReasonNotToStart(final Submission submission)
    {
        final List<DecisionRequirement> decisions = getDecisions(submission);
        if (decisions.isEmpty()) {
            return hasFormReadingDocuments(submission) ? null : ReadingJobs.NOT_SET_UP_MESSAGE;
        }
        final String missing = decisions.stream()
            .filter(decision -> !isMade(submission, decision))
            .map(decision -> SubmissionFiles.findReasonUnreadable(submission, decision.getDocumentRequirementName()))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        if (missing != null) {
            return missing;
        }
        return getUploadedDecisions(submission).isEmpty() ? SubmissionFiles.NO_DOCUMENT_MESSAGE : null;
    }

    /**
     * Whether the submitter confirmed every category: each classification's question has an answer. The category
     * decides which forms apply, so nothing more is read before.
     *
     * @param submission the submission
     * @return {@code true} when every classification that applies has its question answered
     */
    static boolean isCategoryConfirmed(final Submission submission)
    {
        final Set<String> answered = ExtractionFields.getAnswered(submission);
        return getDecisions(submission).stream()
            .filter(ClassificationRequirement.class::isInstance)
            .map(decision -> Objects.requireNonNull(((ClassificationRequirement) decision).getQuestion(),
                "Only a classification holding a question is in the chain"))
            .allMatch(question -> answered.contains(question.getPath()));
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
    static File findFile(final Submission submission, final DecisionRequirement decision)
    {
        return SubmissionFiles.findCurrentFile(submission, decision.getDocumentRequirementName());
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

    /** Whether a form of the schema names documents to be read from. */
    private static boolean hasFormReadingDocuments(final Submission submission)
    {
        return submission.getSchemaVersion().getFormRequirements().stream()
            .anyMatch(form -> !form.getDocumentRequirementNames().isEmpty());
    }

    /** The decisions whose document has an upload, in chain order. */
    private static List<DecisionRequirement> getUploadedDecisions(final Submission submission)
    {
        return getDecisions(submission).stream()
            .filter(decision -> findFile(submission, decision) != null)
            .toList();
    }

    /**
     * Whether the decision is made: a validation has a verdict; a classification was read, or the submitter
     * already answered its question.
     */
    private static boolean isMade(final Submission submission, final DecisionRequirement decision)
    {
        return switch (decision) {
            case final ValidationRequirement validation -> getVerdict(submission, validation) != null;
            case final ClassificationRequirement classification ->
                ExtractionFields.isAnsweredOrRead(submission, classification.getQuestion());
            case null, default -> throw createUnknownDecisionException(decision);
        };
    }

    /**
     * The failure for a decision the chain does not know how to make. The chain holds only validations and
     * classifications, so this means a new kind of decision was added without teaching the chain.
     *
     * @param decision the decision
     * @return the exception to throw
     */
    static IllegalStateException createUnknownDecisionException(final DecisionRequirement decision)
    {
        return new IllegalStateException("The chain cannot make the decision " + decision);
    }

    /** The validation's {@code yes}, {@code no} or {@code unsure}, or {@code null} before the model was asked. */
    private static String getVerdict(final Submission submission, final ValidationRequirement requirement)
    {
        final Validation validation = submission.findValidation(requirement);
        return validation == null ? null : validation.getOutcome();
    }
}
