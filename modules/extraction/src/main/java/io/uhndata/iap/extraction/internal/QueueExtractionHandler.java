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

import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that queues the reading of a submission's answers once its last parse has landed. Queued
 * rather than run here: the reading takes the model minutes, and the request that delivered the parse should not
 * wait for it. A parse that lands while another is still going leaves the queueing to the last one.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class QueueExtractionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "queueExtraction";

    private static final Logger LOGGER = LoggerFactory.getLogger(QueueExtractionHandler.class);

    @Reference
    private JobManager jobManager;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        if (!SubmissionFiles.allParsesSettled(SubmissionFiles.submission(target))) {
            LOGGER.debug("A parse is still going for {}; the last one to land will queue the reading",
                target.getPath());
            return;
        }
        if (this.jobManager.addJob(ExtractAnswersJobConsumer.TOPIC,
            Map.of(ExtractAnswersJobConsumer.SUBMISSION, target.getPath())) == null) {
            throw new PersistenceException("The reading of " + target.getPath() + " could not be queued");
        }
    }
}
