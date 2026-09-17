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
package io.uhndata.iap.documents.internal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseOutcome;
import io.uhndata.iap.documents.spi.ParseOutcomeHandler;

/**
 * Hands a settled job's outcome to the registered {@link ParseOutcomeHandler}s, and drops the job record once one
 * of them has taken it. A job queued without a target is nobody's to take, so its record stays for polling.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParseOutcomeDispatcher.class)
public class ParseOutcomeDispatcher
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ParseOutcomeDispatcher.class);

    private final List<ParseOutcomeHandler> handlers = new CopyOnWriteArrayList<>();

    /**
     * A handler came up.
     *
     * @param handler the handler
     */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    protected void bindHandler(final ParseOutcomeHandler handler)
    {
        this.handlers.add(handler);
    }

    /**
     * A handler went away.
     *
     * @param handler the handler
     */
    protected void unbindHandler(final ParseOutcomeHandler handler)
    {
        this.handlers.remove(handler);
    }

    /**
     * Tell the handlers how a job ended, and delete its record when one of them took the outcome. A record that
     * cannot be deleted is left where it is: the outcome was taken, and a stale record is better than a retry
     * that hands it over twice.
     *
     * @param resolver the session the job node was read through, which is also what deletes it
     * @param jobNode the settled job, its outcome already recorded and committed
     */
    void settle(final ResourceResolver resolver, final Resource jobNode)
    {
        final ValueMap properties = jobNode.getValueMap();
        final String target = properties.get(ParseJob.PN_TARGET, String.class);
        if (target == null) {
            return;
        }
        final ParseOutcome outcome = describe(properties, target);
        boolean taken = false;
        for (final ParseOutcomeHandler handler : this.handlers) {
            try {
                taken |= handler.handle(outcome);
            } catch (final RuntimeException e) {
                LOGGER.error("A parse outcome handler failed on job {}: {}", outcome.jobId(), e.getMessage(), e);
            }
        }
        if (!taken) {
            return;
        }
        try {
            resolver.delete(jobNode);
            resolver.commit();
        } catch (final PersistenceException e) {
            LOGGER.error("Cannot delete the record of parse job {}: {}", outcome.jobId(), e.getMessage(), e);
        }
    }

    private static ParseOutcome describe(final ValueMap properties, final String target)
    {
        final String jobId = properties.get(ParseJob.PN_JOB_ID, String.class);
        final boolean succeeded = ParseJob.STATUS_COMPLETED.equals(properties.get(ParseJob.PN_STATUS, String.class));
        final String[] outputs = properties.get(ParseJob.PN_OUTPUTS, new String[0]);
        return new ParseOutcome(jobId, target, succeeded, properties.get(ParseJob.PN_ERROR, String.class),
            outputs.length > 0 ? outputs[0] : null, outputs.length > 1 ? outputs[1] : null);
    }
}
