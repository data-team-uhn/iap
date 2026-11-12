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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.models.Validation;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * The background job that checks a submission's uploads, the first steps of the intake: whether each document is
 * what its requirement asks for, such as a research proposal. Each validation belongs to one document requirement
 * and has its own prompt, and its template as reference text. A validation whose document was optional and left
 * out is not asked.
 *
 * <p>The job asks the validations one at a time, in the order of their document requirements, each through
 * {@link DocumentValidator}. A {@code no} ends the reading, so the validations after it are not asked.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = JobConsumer.class,
    property = { JobConsumer.PROPERTY_TOPICS + "=" + DecisionJobConsumer.JOB_TOPIC })
public class DecisionJobConsumer implements JobConsumer, ReadingJob
{
    /** The job topic. */
    public static final String JOB_TOPIC = "iap/extraction/decide";

    @Reference
    private ReadingJobs jobs;

    @Reference
    private DocumentValidator validator;

    @Override
    public JobResult process(final Job job)
    {
        return this.jobs.run(job, this);
    }

    /**
     * Whether nothing is left to validate: a validation said no, which ends the reading, or every validation whose
     * document was uploaded has a verdict.
     *
     * @param submission the submission to read
     * @return {@code true} when the model has nothing left to be asked
     */
    @Override
    public boolean isAlreadyRead(final Submission submission)
    {
        final List<String> verdicts = getUploadedValidations(submission).stream()
            .map(requirement -> getVerdict(submission, requirement))
            .toList();
        return !verdicts.isEmpty()
            && (verdicts.contains(Validation.OUTCOME_NO) || verdicts.stream().allMatch(Objects::nonNull));
    }

    /**
     * Why the validation cannot start: the schema has no validation, a document still to be validated is missing,
     * or none of their documents was uploaded.
     *
     * @param resolver this job's session
     * @param submission the submission to read
     * @return the message for the submitter, or {@code null} when every document still to be validated is parsed
     */
    @Override
    public String findReasonNotToStart(final ResourceResolver resolver, final Submission submission)
    {
        final List<ValidationRequirement> validations = getValidations(submission);
        if (validations.isEmpty()) {
            return ReadingJobs.NOT_SET_UP_MESSAGE;
        }
        final String missing = validations.stream()
            .filter(requirement -> getVerdict(submission, requirement) == null && isMissing(submission, requirement))
            .map(requirement -> describeMissing(getDocument(submission, requirement).getLabel()))
            .findFirst()
            .orElse(null);
        if (missing != null) {
            return missing;
        }
        return getPendingValidations(submission).isEmpty() ? SubmissionFiles.NO_DOCUMENT_MESSAGE : null;
    }

    /**
     * What the submitter is told when a document to validate is missing.
     *
     * @param label the document requirement's label
     * @return the message
     */
    static String describeMissing(final String label)
    {
        return "The " + label + " is missing, please upload it";
    }

    /**
     * Whether a document the validation reads should be there and cannot be read: a required one was never uploaded,
     * which the submission view should have prevented, or its parse failed. An optional one left out is not missing.
     */
    private static boolean isMissing(final Submission submission, final ValidationRequirement requirement)
    {
        final File file = getFile(submission, requirement);
        if (file == null) {
            final DocumentRequirement document = getDocument(submission, requirement);
            return document.isRequired() && submission.isApplicable(document);
        }
        // Every parse has settled by now, so one that is not completed failed or was stopped
        return !ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus());
    }

    @Override
    public void read(final Resource submission, final String jobId) throws WorkflowException
    {
        final ResourceResolver resolver = submission.getResourceResolver();
        final List<ValidationRequirement> pending = getPendingValidations(SubmissionFiles.getSubmission(submission));
        for (int next = 0; next < pending.size(); next++) {
            // Each verdict is saved through the engine's session, which this one sees only after a refresh
            resolver.refresh();
            final Resource current = resolver.getResource(submission.getPath());
            if (current == null || next > 0 && !ExtractionStatus.isRunning(current)) {
                // A no or a Stop ended the reading
                return;
            }
            final ValidationRequirement requirement = pending.get(next);
            if (!this.validator.validate(current, jobId, requirement,
                getFile(SubmissionFiles.getSubmission(current), requirement), next == pending.size() - 1)) {
                return;
            }
        }
    }

    /**
     * The schema's validations that apply and name one of its documents, in the order of those documents.
     */
    private static List<ValidationRequirement> getValidations(final Submission submission)
    {
        final List<String> documents = submission.getSchemaVersion().getDocumentRequirements().stream()
            .map(DocumentRequirement::getName)
            .toList();
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(ValidationRequirement.class::isInstance)
            .map(ValidationRequirement.class::cast)
            .filter(requirement -> documents.contains(requirement.getDocumentRequirementName())
                && submission.isApplicable(requirement))
            .sorted(Comparator.comparingInt(requirement -> documents.indexOf(requirement.getDocumentRequirementName())))
            .toList();
    }

    /** The validations whose document has an upload, in document order. */
    private static List<ValidationRequirement> getUploadedValidations(final Submission submission)
    {
        return getValidations(submission).stream()
            .filter(requirement -> getFile(submission, requirement) != null)
            .toList();
    }

    /** The validations whose document has an upload and that have no verdict yet, in document order. */
    private static List<ValidationRequirement> getPendingValidations(final Submission submission)
    {
        return getUploadedValidations(submission).stream()
            .filter(requirement -> getVerdict(submission, requirement) == null)
            .toList();
    }

    /** The document requirement the validation reads; {@link #getValidations} keeps only those that have one. */
    private static DocumentRequirement getDocument(final Submission submission,
        final ValidationRequirement requirement)
    {
        return SubmissionFiles.findDocumentRequirement(submission, requirement.getDocumentRequirementName());
    }

    private static File getFile(final Submission submission, final ValidationRequirement requirement)
    {
        return SubmissionFiles.getCurrentFile(submission, requirement.getDocumentRequirementName());
    }

    /** The validation's {@code yes}, {@code no} or {@code unsure}, or {@code null} before the model was asked. */
    private static String getVerdict(final Submission submission, final ValidationRequirement requirement)
    {
        final Validation validation = submission.findValidation(requirement);
        return validation == null ? null : validation.getOutcome();
    }
}
