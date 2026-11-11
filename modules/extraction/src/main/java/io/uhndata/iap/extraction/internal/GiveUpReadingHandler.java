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

import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Service task {@code giveUpReading}: a reading job could not read a submission. It marks the reading failed, which
 * stops the spinner in the view, and releases the claim, so a later parse or a read again request can start a new
 * reading. Run by the {@code readingFailed} system workflow, which only the extraction module's own user may fire.
 *
 * <p>It leaves a reading that is no longer the job's alone. A stop takes the claim down, and a read again request
 * may have queued a new reading since, whose status a late give-up must not overwrite.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class GiveUpReadingHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "giveUpReading";

    /** The event payload key holding the message for the person looking at the submission. */
    static final String MESSAGE_KEY = "message";

    /**
     * The event payload key saying whether the job had claimed the reading. A job that had not may also give up a
     * reading nobody has claimed yet.
     */
    static final String CLAIMED_KEY = "claimed";

    private static final Logger LOGGER = LoggerFactory.getLogger(GiveUpReadingHandler.class);

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
        final String message = Payloads.requireText(event, MESSAGE_KEY, "The event does not say why the job gave up");
        ExtractionStatus.checkOut(target);
        final ModifiableValueMap properties = target.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to give up the reading of " + target.getPath());
        }
        final String claimedBy = properties.get(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.FALSE)
            .booleanValue() ? properties.get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class) : null;
        final boolean hadClaimed = Boolean.TRUE.equals(event.get(CLAIMED_KEY));
        if (!jobId.equals(claimedBy) && (hadClaimed || claimedBy != null)) {
            LOGGER.info("Not giving up the reading of {}: it is now {}'s", target.getPath(), claimedBy);
            return;
        }
        ExtractionStatus.releaseClaim(properties);
        ExtractionStatus.recordStatus(target, ExtractionStatus.STATUS_FAILED, message);
    }
}
