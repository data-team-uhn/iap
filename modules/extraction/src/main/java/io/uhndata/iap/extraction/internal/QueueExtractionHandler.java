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
 * The service task that asks for the reading of a submission's answers. Queued rather than run here: the reading
 * takes the model minutes, and the request that delivered the parse should not wait for it.
 *
 * <p>Every parse that lands queues one, and the job decides whether its turn has come. Deciding here instead
 * meant asking "has every other parse landed?" from inside a transaction that has not committed, so two parses
 * landing at once could each see the other as still going and neither would queue anything - a submission left
 * at {@code running} with nothing left to move it on. The job looks at committed state and claims the reading,
 * so the question is asked where it can be answered.</p>
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
    public void execute(final WorkflowTaskContext context) throws PersistenceException
    {
        final Resource target = context.getTarget();
        if (this.jobManager.addJob(ExtractAnswersJobConsumer.TOPIC,
            Map.of(ExtractAnswersJobConsumer.SUBMISSION, target.getPath())) == null) {
            throw new PersistenceException("The reading of " + target.getPath() + " could not be queued");
        }
        LOGGER.info("Reading queued: submission={}", target.getPath());
    }
}
