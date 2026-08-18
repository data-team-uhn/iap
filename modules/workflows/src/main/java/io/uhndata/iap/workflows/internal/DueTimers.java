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
package io.uhndata.iap.workflows.internal;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * Delivers the deadlines that have passed: the clock's side of the engine's door.
 *
 * <p>A boundary timer is the one thing in a workflow that nobody fires. Every other event arrives because somebody
 * did something — posted, decided, completed — and the engine's entry point takes the actor from the session that
 * asked. Time has no session, so this is what stands in for one: it finds the tasks whose deadline has passed and
 * hands each to {@link WorkflowEngine#receiveEvent} as an ordinary {@code timeout} event, so that a timer firing
 * goes through the same door, the same authorization rules and the same one-commit guarantee as everything else.</p>
 *
 * <p>Polling rather than a scheduled job per deadline: a deadline lives in the repository, so it survives a restart
 * and a failover, which a scheduler's in-memory job does not. The cost is that a timer fires at the first sweep
 * after it is due rather than to the second, which is the right trade for deadlines measured in days.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class DueTimers implements Runnable
{
    /** How often the deadlines are swept, by default: every five minutes. */
    static final String DEFAULT_SCHEDULE = "0 0/5 * * * ?";

    /** The name the sweep is scheduled under, so that it replaces itself rather than accumulating. */
    private static final String JOB_NAME = "iap-workflow-due-timers";

    /** The service user everything the engine reads and writes goes through. */
    private static final String SUBSERVICE = "workflows";

    private static final Logger LOGGER = LoggerFactory.getLogger(DueTimers.class);

    /**
     * The open tasks whose deadline has passed. Ordered by deadline so that a sweep finding more than it can
     * deliver leaves the newest waiting rather than an arbitrary set.
     */
    private static final String DUE_TASKS =
        "SELECT * FROM [wf:TaskInstance] AS task WHERE task.[status] = 'created' AND task.[dueDate] <= $now"
            + " ORDER BY task.[dueDate] ASC";

    @Reference
    private Scheduler scheduler;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private WorkflowEngine engine;

    @Activate
    protected void activate()
    {
        final ScheduleOptions options = this.scheduler.EXPR(DEFAULT_SCHEDULE);
        options.name(JOB_NAME);
        // One sweep at a time: two overlapping ones would both find the same overdue task, and the second would
        // deliver a timeout to a task the first has already cancelled
        options.canRunConcurrently(false);
        this.scheduler.schedule(this, options);
        LOGGER.info("Scheduled the workflow deadline sweep");
    }

    @Deactivate
    protected void deactivate()
    {
        this.scheduler.unschedule(JOB_NAME);
    }

    @Override
    public void run()
    {
        try (ResourceResolver resolver =
            this.resolverFactory.getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            for (final String path : overdue(resolver)) {
                final Resource task = resolver.getResource(path);
                if (task != null) {
                    fire(task);
                }
            }
        } catch (final LoginException e) {
            LOGGER.error("The workflow engine's service user is not available, so no deadline can be delivered", e);
        } catch (final RepositoryException e) {
            LOGGER.error("Could not look for passed deadlines", e);
        }
    }

    /**
     * The paths of the tasks whose deadline has passed, read through the engine's own session. Paths rather than
     * resources, and read out in one go, because the query's own session is the wrong thing to be holding while
     * each delivery opens, writes and commits its own.
     *
     * <p>The deadline is bound as a value rather than written into the statement: it is a timestamp the engine
     * itself produced, but a query built by concatenation is a habit worth not having.</p>
     *
     * @param resolver the engine's session
     * @return the overdue tasks' paths, in deadline order
     * @throws RepositoryException when the query cannot be run
     */
    private static List<String> overdue(final ResourceResolver resolver) throws RepositoryException
    {
        final Session session = Objects.requireNonNull(resolver.adaptTo(Session.class),
            "The engine's own resolver is always JCR-backed");
        final Query query = session.getWorkspace().getQueryManager().createQuery(DUE_TASKS, Query.JCR_SQL2);
        query.bindValue("now", session.getValueFactory().createValue(Calendar.getInstance()));
        final List<String> paths = new ArrayList<>();
        for (final NodeIterator nodes = query.execute().getNodes(); nodes.hasNext();) {
            paths.add(nodes.nextNode().getPath());
        }
        return paths;
    }

    /**
     * Delivers one passed deadline. A failure is logged and the sweep carries on: one broken definition must not
     * stop every other deadline in the repository from being met.
     *
     * @param task the overdue task
     */
    private void fire(final Resource task)
    {
        try {
            this.engine.receiveEvent(task, new WorkflowEvent(TaskCompletion.TIMEOUT_EVENT, Map.of()));
            LOGGER.debug("Delivered the passed deadline of {}", task.getPath());
        } catch (final WorkflowException e) {
            LOGGER.error("Could not deliver the passed deadline of {}: {}", task.getPath(), e.getMessage(), e);
        }
    }
}
