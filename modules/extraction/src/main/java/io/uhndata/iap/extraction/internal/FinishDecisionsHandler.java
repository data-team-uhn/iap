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

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that ends the checks of a submission's uploads once every document was validated and
 * categorised: the reading is {@code done}, with the job's message for the submitter, and the claim is released.
 *
 * <p>Only the job that holds the reading may end it: after a Stop or a Read again the reading is someone else's.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class FinishDecisionsHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "finishDecisions";

    /** The payload entry holding the message for the submitter; absent when there is nothing to say. */
    static final String MESSAGE_KEY = "message";

    private static final Logger LOGGER = LoggerFactory.getLogger(FinishDecisionsHandler.class);

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
        ExtractionStatus.checkOut(target);
        final ModifiableValueMap properties = target.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to finish the reading of " + target.getPath());
        }
        if (!jobId.equals(properties.get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class))) {
            throw new InvalidStateException("The reading of " + target.getPath() + " is no longer this job's");
        }
        ExtractionStatus.releaseClaim(properties);
        final Object message = event.get(MESSAGE_KEY);
        ExtractionStatus.recordStatus(target, ExtractionStatus.STATUS_DONE,
            message instanceof final String text ? text : null);
        LOGGER.info("Document checks done: submission={}", target.getPath());
    }
}
