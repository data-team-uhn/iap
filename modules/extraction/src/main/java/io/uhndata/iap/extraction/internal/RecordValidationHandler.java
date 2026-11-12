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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Validation;
import io.uhndata.iap.utils.ReferenceUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that saves what the model decided about an upload: one run under the submission's validation
 * for that requirement, made first when there is none. When the verdict is no, the reading ends there and the
 * request is marked blocked, with "Unable to identify the" and the label of the document requirement.
 *
 * <p>Only the job that holds the reading may save it: after a Stop or a Read again the reading is someone else's,
 * and a late verdict must not land on it. The claim is released with the job's last verdict, or with a no, so the
 * intake's next job can claim the reading.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class RecordValidationHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "recordValidation";

    /** The payload entry naming the validation requirement, by path. */
    static final String REQUIREMENT_KEY = "requirement";

    /** The payload entry naming the file that was read, by path. */
    static final String FILE_KEY = "file";

    /** The payload entry holding what the model answered, a {@link FieldResult}. */
    static final String RESULT_KEY = "result";

    /** The payload entry saying whether this is the job's last verdict, so the claim is released. */
    static final String LAST_KEY = "last";

    /** What the submitter is told when the model is sure an upload is not its document; the label follows. */
    static final String NOT_IDENTIFIED_MESSAGE = "Unable to identify the ";

    private static final String REQUIREMENT_PROPERTY = "requirement";

    private static final Logger LOGGER = LoggerFactory.getLogger(RecordValidationHandler.class);

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final WorkflowEvent event = context.getEvent();
        final String jobId =
            Payloads.requireText(event, ClaimReadingHandler.JOB_KEY, "The event does not name the job");
        if (!(event.get(RESULT_KEY) instanceof final FieldResult result)) {
            throw new InvalidPayloadException("The event does not carry what the model answered");
        }
        final ResourceResolver resolver = context.getResourceResolver();
        final Resource requirement = getRequirement(resolver, Payloads.requireText(event, REQUIREMENT_KEY,
            "The event does not name the validation"));
        final ValidationRequirement validationRequirement = Objects.requireNonNull(
            requirement.adaptTo(ValidationRequirement.class), "A validation requirement node reads as one");
        final File file = getFile(resolver, Payloads.requireText(event, FILE_KEY,
            "The event does not name the document that was read"));
        ExtractionStatus.checkOut(target);
        final ModifiableValueMap properties = target.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record the validation of " + target.getPath());
        }
        if (!jobId.equals(properties.get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class))) {
            throw new InvalidStateException("The reading of " + target.getPath() + " is no longer this job's");
        }
        final Resource validation = getOrCreateValidation(resolver, target, requirement, validationRequirement);
        ExtractedAnswers.writeRun(resolver, validation, result, List.of(file));
        final String outcome = Objects.requireNonNull(validation.adaptTo(Validation.class),
            "A validation node reads as a validation").getOutcome();
        final boolean rejected = Validation.OUTCOME_NO.equals(outcome);
        if (rejected || Boolean.TRUE.equals(event.get(LAST_KEY))) {
            ExtractionStatus.releaseClaim(properties);
        }
        if (rejected) {
            ExtractionStatus.recordStatus(target, ExtractionStatus.STATUS_DONE,
                NOT_IDENTIFIED_MESSAGE + getDocumentLabel(target, validationRequirement));
            ExtractionStatus.markBlocked(target);
        }
        LOGGER.info("Validation recorded: submission={} requirement={} outcome={}", target.getPath(),
            requirement.getPath(), outcome);
    }

    private static Resource getRequirement(final ResourceResolver resolver, final String path)
        throws InvalidPayloadException
    {
        final Resource requirement = resolver.getResource(path);
        if (requirement == null || !requirement.isResourceType(ValidationRequirement.RESOURCE_TYPE)) {
            throw new InvalidPayloadException("The event names no validation requirement at " + path);
        }
        return requirement;
    }

    /** The label of the document the validation reads, the one the submitter was asked to upload. */
    private static String getDocumentLabel(final Resource target, final ValidationRequirement requirement)
    {
        final DocumentRequirement document = SubmissionFiles.findDocumentRequirement(
            SubmissionFiles.getSubmission(target), requirement.getDocumentRequirementName());
        return document == null ? requirement.getLabel() : document.getLabel();
    }

    private static File getFile(final ResourceResolver resolver, final String path) throws InvalidPayloadException
    {
        final Resource file = resolver.getResource(path);
        if (file == null) {
            throw new InvalidPayloadException("The event names no document at " + path);
        }
        return file.adaptTo(File.class);
    }

    /** The submission's validation for the requirement, made when this is its first run. */
    private static Resource getOrCreateValidation(final ResourceResolver resolver, final Resource target,
        final Resource requirement, final ValidationRequirement validationRequirement) throws PersistenceException
    {
        final Validation existing = SubmissionFiles.getSubmission(target).findValidation(validationRequirement);
        if (existing != null) {
            return resolver.getResource(existing.getPath());
        }
        final Resource created = resolver.create(target, UUID.randomUUID().toString(),
            Map.of(JcrConstants.JCR_PRIMARYTYPE, "sub:Validation"));
        ReferenceUtils.setReference(created, REQUIREMENT_PROPERTY, requirement);
        return created;
    }
}
