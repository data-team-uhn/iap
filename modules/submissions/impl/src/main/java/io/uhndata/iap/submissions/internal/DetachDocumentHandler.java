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

import java.util.Objects;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that removes the document attached for a requirement, with all its versions.
 *
 * <p>Under the same rule as attaching: the person who raised the request, while it is still a draft. The whole
 * document goes, since removing says the file was wrong, not that it has a history worth keeping.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class DetachDocumentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "detachDocument";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "The detach workflow only applies to submissions");
        SaveAnswersHandler.checkMayEdit(submission, context.getActor());

        final String named = Payloads.requireText(context.getEvent(), AttachDocumentHandler.REQUIREMENT_PARAMETER,
            "The request does not say which document to remove");
        // Not filtered by its condition, so a document left from a requirement that stopped applying can still go
        final DocumentRequirement requirement = AttachDocumentHandler.findRequirement(submission, named)
            .orElseThrow(() -> new InvalidPayloadException(
                "There is no document requirement " + named + " in this request"));
        final Document attached = submission.getDocuments().stream()
            .filter(document -> document.isFulfilling(requirement))
            .findFirst()
            .orElseThrow(() -> new InvalidPayloadException(
                "Nothing is attached for " + requirement.getLabel() + ", so there is nothing to remove"));

        final ResourceResolver resolver = context.getResourceResolver();
        final Resource document = Objects.requireNonNull(resolver.getResource(attached.getPath()),
            "A document the submission just listed can be read by the same resolver");
        VersioningUtils.checkOut(target);
        resolver.delete(document);
    }
}
