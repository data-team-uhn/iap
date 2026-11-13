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

import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Service task {@code claimReading}: a reading job takes the reading of a submission, so two jobs never both pay a
 * model to read the same document. Run by the {@code readingStarted} system workflow, which only the extraction
 * module's own user may fire.
 *
 * <p>The claim holds the job's id. When two jobs both find it unset, their writes differ, so the second commit
 * conflicts and the engine refuses it. A claim that already holds this job's id is still this job's: Sling runs a
 * job again, with the same id, when the instance stopped before the job finished.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ClaimReadingHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "claimReading";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final String jobId = ReadingEvents.getJobId(context.getEvent());
        final ModifiableValueMap properties = ExtractionStatus.getWritableProperties(target);
        if (properties.get(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.FALSE).booleanValue()
            && !jobId.equals(properties.get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class))) {
            throw new InvalidStateException("Another job is already reading " + target.getPath());
        }
        properties.put(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.TRUE);
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, jobId);
    }
}
