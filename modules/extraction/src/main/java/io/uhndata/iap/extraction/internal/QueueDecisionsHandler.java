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
 * <p>Every finished parse queues a job, and the job decides whether the reading can start ({@link ReadingJobs}).
 * This step cannot: two parses ending at once would each see the other still going, and neither would start
 * the reading.</p>
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
    private ReadingQueue readingQueue;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context)
    {
        this.readingQueue.queueAfterCommit(context, DecisionJobConsumer.JOB_TOPIC);
        LOGGER.info("Document checks queued: submission={}", context.getTarget().getPath());
    }
}
