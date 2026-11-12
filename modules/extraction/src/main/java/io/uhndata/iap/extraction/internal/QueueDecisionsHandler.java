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

import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that queues the checks of a submission's uploads as a background job, the first steps of the
 * intake. It is queued, not run here, because the model takes a while and the request that delivered the parse
 * should not wait.
 *
 * <p>Every finished parse queues one job, and the job decides whether the reading can start. This task cannot
 * decide, because its transaction has not committed yet: two parses ending at the same time could each see the
 * other as still going, and neither would queue a reading, leaving the submission at {@code running} for good.
 * The job sees committed data and claims the reading.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class QueueDecisionsHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "queueDecisions";

    private static final Logger LOGGER = LoggerFactory.getLogger(QueueDecisionsHandler.class);

    @Reference
    private JobManager jobManager;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws PersistenceException
    {
        final Resource target = context.getTarget();
        if (this.jobManager.addJob(DecisionJobConsumer.JOB_TOPIC,
            Map.of(ReadingJobs.SUBMISSION_PROPERTY, target.getPath())) == null) {
            throw new PersistenceException("The document checks of " + target.getPath() + " could not be queued");
        }
        LOGGER.info("Document checks queued: submission={}", target.getPath());
    }
}
