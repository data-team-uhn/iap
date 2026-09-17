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

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * Runs the reading of a submission's answers in the background: fires the {@code extractAnswers} event on the
 * submission, and the system workflow catching it does the rest - gate, category, intake - in one commit. The same
 * event can be fired from the submission itself, {@code POST <submission>.extractAnswers.json}, to read it again.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = JobConsumer.class,
    property = { JobConsumer.PROPERTY_TOPICS + "=" + ExtractAnswersJobConsumer.TOPIC })
public class ExtractAnswersJobConsumer implements JobConsumer
{
    /** The job topic. */
    public static final String TOPIC = "iap/extraction/extract";

    /** The job property naming the submission to read. */
    public static final String SUBMISSION = "submission";

    /** The event the reading is. */
    static final String EVENT = "extractAnswers";

    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractAnswersJobConsumer.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private WorkflowEngine engine;

    @Override
    public JobResult process(final Job job)
    {
        final String path = job.getProperty(SUBMISSION, String.class);
        if (path == null) {
            LOGGER.warn("Dropping an extraction job that names no submission");
            return JobResult.CANCEL;
        }
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, ParseCompletionHandler.SUBSERVICE))) {
            final Resource submission = resolver.getResource(path);
            if (submission == null) {
                LOGGER.warn("Dropping the extraction job for {}: it is gone", path);
                return JobResult.CANCEL;
            }
            this.engine.receiveEvent(submission, new WorkflowEvent(EVENT, Map.of()));
            return JobResult.OK;
        } catch (final LoginException e) {
            LOGGER.error("Cannot read submissions to extract answers from {}: {}", path, e.getMessage(), e);
            return JobResult.CANCEL;
        } catch (final WorkflowException e) {
            // Not retried: a second attempt would ask the model the same questions again, at the same cost
            LOGGER.error("Extracting answers from {} failed: {}", path, e.getMessage(), e);
            return JobResult.CANCEL;
        }
    }
}
