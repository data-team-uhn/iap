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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.DecisionRequirement;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.models.Validation;

/**
 * The decisions {@link DecisionJobConsumer} makes about a submission's uploads, in the order it makes them: for
 * each document, in the order of the schema's document requirements, its validation, then its classification.
 * Only decisions that name one of the schema's documents count. Which are pending, made or rejected counts only
 * those that apply; the parse gate reads every decision, applying or not.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = DecisionChain.class)
public class DecisionChain
{
    /** What the submitter is told when the intake took a sure no for an upload; the document's label follows. */
    static final String NOT_IDENTIFIED_MESSAGE = "Unable to identify the ";

    private final IntakeTasks intakeTasks = new IntakeTasks();

    /**
     * Why the chain cannot start: nothing in the schema reads the documents, a document still to be checked is
     * missing, a document that is read did not parse, or nothing that is read was uploaded. A schema with no
     * validation or classification, but with a form that names its documents, starts and ends at once. Nothing is
     * checked, and the forms are read on Next. A chain whose every decision is made also ends at once, such as when
     * a document is parsed again.
     *
     * @param submission the submission
     * @return the message for the submitter, or {@code null} when every document still to be checked is parsed
     */
    String findReasonNotToStart(final Submission submission)
    {
        final List<String> read = getReadDocumentNames(submission);
        if (read.isEmpty()) {
            return ReadingJobs.NOT_SET_UP_MESSAGE;
        }
        final Set<String> handedOver = this.intakeTasks.getHandedOverNames(submission);
        final Set<String> answeredOrRead = ExtractionFields.getAnsweredOrRead(submission);
        final String missing = getDecisions(submission).stream()
            .filter(decision -> !isMade(submission, decision, handedOver, answeredOrRead))
            .map(decision -> SubmissionFiles.findReasonUnreadable(submission, decision.getDocumentRequirementName()))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        if (missing != null) {
            return missing;
        }
        // Ended as done, a failed parse would never reach the submitter, who could not try it again
        final String failed = read.stream()
            .map(name -> findParseFailure(submission, name))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        if (failed != null) {
            return failed;
        }
        return read.stream().anyMatch(name -> SubmissionFiles.findCurrentFile(submission, name) != null)
            ? null : SubmissionFiles.NO_DOCUMENT_MESSAGE;
    }

    /** Why a document's latest upload cannot be read when its parse failed, or {@code null}. */
    private String findParseFailure(final Submission submission, final String name)
    {
        final File file = SubmissionFiles.findCurrentFile(submission, name);
        return file != null && ParsePropertyNames.STATUS_FAILED.equals(file.getParseStatus())
            ? SubmissionFiles.findReasonUnreadable(submission, name) : null;
    }

    /**
     * The documents something reads: a check or a form, whatever its condition. A condition may hold only once the
     * submitter answers, or once the category is confirmed, and the upload must be parsed by then.
     *
     * @param submission the submission
     * @return the names of the document requirements read, in schema order; empty when nothing reads the uploads
     */
    List<String> getReadDocumentNames(final Submission submission)
    {
        final Set<String> read = Stream.<Requirement>concat(getEveryDecision(submission).stream(),
            submission.getSchemaVersion().getFormRequirements().stream())
            .flatMap(requirement -> requirement.getReadDocumentNames().stream())
            .collect(Collectors.toSet());
        return submission.getSchemaVersion().getDocumentRequirements().stream()
            .map(DocumentRequirement::getName)
            .filter(read::contains)
            .collect(Collectors.toList());
    }

    /**
     * Whether the submitter confirmed every category: each classification's question has an answer. The category
     * decides which forms apply, so nothing more is read before. A document that was not uploaded has no category
     * to confirm.
     *
     * @param submission the submission
     * @return {@code true} when every classification that applies to an upload has its question answered
     */
    boolean isCategoryConfirmed(final Submission submission)
    {
        final Set<String> answered = ExtractionFields.getAnswered(submission);
        return getUploadedDecisions(submission).stream()
            .filter(ClassificationRequirement.class::isInstance)
            .map(decision -> Objects.requireNonNull(((ClassificationRequirement) decision).getQuestion(),
                "A classification's question is a mandatory reference"))
            .allMatch(question -> answered.contains(question.getPath()));
    }

    /**
     * The decisions whose document has an upload and that are not made yet, in chain order.
     *
     * @param submission the submission
     * @return the decisions still to make
     */
    List<DecisionRequirement> getPendingDecisions(final Submission submission)
    {
        final Set<String> handedOver = this.intakeTasks.getHandedOverNames(submission);
        if (findRejection(submission, handedOver) != null) {
            // The intake ended on that no, so nothing after it is decided
            return List.of();
        }
        final Set<String> answeredOrRead = ExtractionFields.getAnsweredOrRead(submission);
        return getUploadedDecisions(submission).stream()
            .filter(decision -> !isMade(submission, decision, handedOver, answeredOrRead))
            .toList();
    }

    /**
     * What the submitter is told when the intake took a sure no: "Unable to identify the" and the label of the
     * document asked for. Only a validation that applies counts. The same document would get the same answer, so
     * the request goes no further with it.
     *
     * @param submission the submission
     * @return the message for the first such validation in chain order, or {@code null} when the request was not
     *         rejected
     */
    String findRejectionMessage(final Submission submission)
    {
        final ValidationRequirement rejection =
            findRejection(submission, this.intakeTasks.getHandedOverNames(submission));
        return rejection == null ? null : NOT_IDENTIFIED_MESSAGE + Objects.requireNonNull(
            SubmissionFiles.findDocumentRequirement(submission, rejection.getDocumentRequirementName()),
            "Only a validation reading one of the schema's documents is in the chain").getLabel();
    }

    private ValidationRequirement findRejection(final Submission submission, final Set<String> handedOver)
    {
        return getDecisions(submission).stream()
            .filter(ValidationRequirement.class::isInstance)
            .map(ValidationRequirement.class::cast)
            .filter(validation -> handedOver.contains(validation.getName()) && isNo(submission, validation))
            .findFirst()
            .orElse(null);
    }

    private static boolean isNo(final Submission submission, final ValidationRequirement validation)
    {
        final Validation verdict = submission.findValidation(validation);
        return verdict != null && Validation.OUTCOME_NO.equals(verdict.getOutcome());
    }

    /**
     * The latest upload of the document the decision reads.
     *
     * @param submission the submission
     * @param decision the decision
     * @return the upload, or {@code null} when the document was not uploaded
     */
    File findFile(final Submission submission, final DecisionRequirement decision)
    {
        return SubmissionFiles.findCurrentFile(submission, decision.getDocumentRequirementName());
    }

    /** The decisions that apply now, in chain order. */
    private List<DecisionRequirement> getDecisions(final Submission submission)
    {
        return getEveryDecision(submission).stream()
            .filter(submission::isApplicable)
            .toList();
    }

    /** Every decision, applying or not, in chain order: by document, and within one document the validation first. */
    private List<DecisionRequirement> getEveryDecision(final Submission submission)
    {
        final List<String> documents = submission.getSchemaVersion().getDocumentRequirements().stream()
            .map(DocumentRequirement::getName)
            .toList();
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(DecisionRequirement.class::isInstance)
            .map(DecisionRequirement.class::cast)
            .filter(decision -> documents.contains(decision.getDocumentRequirementName()))
            .sorted(Comparator.<DecisionRequirement>comparingInt(
                decision -> documents.indexOf(decision.getDocumentRequirementName()))
                .thenComparing(decision -> decision instanceof ClassificationRequirement))
            .toList();
    }

    /** Those of the decisions whose document has an upload, in chain order. */
    private List<DecisionRequirement> getUploadedDecisions(final Submission submission)
    {
        return getDecisions(submission).stream()
            .filter(decision -> findFile(submission, decision) != null)
            .toList();
    }

    /**
     * Whether the validation has a verdict saved, whether or not the intake took it yet.
     *
     * @param submission the submission
     * @param validation the validation
     * @return {@code true} once the model's verdict is saved
     */
    boolean hasVerdict(final Submission submission, final ValidationRequirement validation)
    {
        final Validation verdict = submission.findValidation(validation);
        return verdict != null && verdict.getOutcome() != null;
    }

    /**
     * Whether the decision is made: a validation has a verdict and the intake took it ({@code handedOver}); a
     * classification was read, or the submitter already answered its question ({@code answeredOrRead}). A verdict
     * the intake did not take yet was saved but not handed over, so the next job hands it over.
     */
    private boolean isMade(final Submission submission, final DecisionRequirement decision,
        final Set<String> handedOver, final Set<String> answeredOrRead)
    {
        return switch (decision) {
            case final ValidationRequirement validation ->
                hasVerdict(submission, validation) && handedOver.contains(validation.getName());
            case final ClassificationRequirement classification -> answeredOrRead.contains(Objects.requireNonNull(
                classification.getQuestion(), "A classification's question is a mandatory reference").getPath());
            case null -> throw new IllegalStateException("The chain holds no missing decision");
        };
    }
}
