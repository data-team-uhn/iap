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
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
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

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.TaskInstance;

/**
 * Delivers the deadlines that have passed to the workflow engine.
 *
 * <p>A boundary timer is the one thing in a workflow that happens without a user involved. Every other event
 * arrives because somebody did something, and the engine takes the actor from the session that asked. This sweep
 * finds the tasks whose deadline has passed. It hands each to {@link WorkflowEngine#receiveEvent} as an ordinary
 * {@code timeout} event, through a session of the {@value TaskCompletion#TIMER_USER} service user. A timer firing
 * therefore meets the same performer check and the same single commit as any other event. What it does is
 * recorded as the clock's doing.</p>
 *
 * <p>The deadlines are polled because a deadline stored in the repository survives a restart and a failover. A
 * scheduler's in-memory job per deadline does not. A timer fires at the first sweep after it is due.</p>
 *
 * <p>A delivery that fails is tried again by the next sweep, up to {@value #MAX_DELIVERY_FAILURES} times. After
 * that the task keeps its deadline but is left alone, and the give-up is recorded for an administrator.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class DueTimers implements Runnable
{
    /** How often the deadlines are swept, by default: every five minutes. */
    static final String DEFAULT_SCHEDULE = "0 0/5 * * * ?";

    /** How many failed deliveries of one deadline the sweep makes before it leaves the task alone. */
    static final long MAX_DELIVERY_FAILURES = 3;

    /** Where a task counts its failed deliveries. */
    static final String DELIVERY_FAILURES_PROPERTY = "deliveryFailures";

    /** The subservice deadlines are delivered through, mapped to the {@value TaskCompletion#TIMER_USER} user. */
    static final String TIMER_SUBSERVICE = "timer";

    /** The name the sweep is scheduled under, so that it replaces itself rather than accumulating. */
    private static final String JOB_NAME = "iap-workflow-due-timers";

    /** The operation a failure is recorded under, when the sweep as a whole fails. */
    private static final String SWEEP = "sweep";

    private static final String STATUS_PROPERTY = "status";

    private static final Logger LOGGER = LoggerFactory.getLogger(DueTimers.class);

    /** The open tasks whose deadline has passed and not been given up on, oldest deadline first. */
    private static final String DUE_TASKS = "SELECT * FROM [wf:TaskInstance] AS task"
        + " WHERE task.[status] = '" + TaskInstance.OPEN_STATUS + "' AND task.[dueDate] <= $now"
        + " AND (task.[" + DELIVERY_FAILURES_PROPERTY + "] IS NULL"
        + " OR task.[" + DELIVERY_FAILURES_PROPERTY + "] < " + MAX_DELIVERY_FAILURES + ")"
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
        // One sweep at a time, on one instance of a cluster. Two overlapping sweeps would find the same overdue task,
        // and the second would deliver a timeout to a task the first has already cancelled.
        options.canRunConcurrently(false);
        options.onLeaderOnly(true);
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
        try (ResourceResolver resolver = login(WorkflowEngineImpl.SUBSERVICE_NAME);
            ResourceResolver timer = login(TIMER_SUBSERVICE)) {
            for (final String path : overdue(resolver)) {
                fire(resolver, timer, path);
            }
        } catch (final LoginException e) {
            LOGGER.error("A service user the sweep needs is not available, so no deadline can be delivered", e);
            ErrorLogger.logError(e, ErrorContext.of(DueTimers.class, SWEEP));
        } catch (final RepositoryException e) {
            LOGGER.error("Could not look for passed deadlines", e);
            ErrorLogger.logError(e, ErrorContext.of(DueTimers.class, SWEEP));
        }
    }

    /**
     * Opens a session of one of this bundle's service users.
     *
     * @param subservice which one
     * @return the session, to be closed by the caller
     * @throws LoginException when that service user is not available
     */
    private ResourceResolver login(final String subservice) throws LoginException
    {
        return this.resolverFactory.getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, subservice));
    }

    /**
     * Find the paths of the tasks whose deadline has passed, read through the engine's own session.
     *
     * <p>Read in one go, so the query's results are not still being iterated while deliveries commit. Each task is
     * looked at again just before its delivery, since an earlier delivery or a person may have closed it since.</p>
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
     * Delivers one passed deadline, as a {@code timeout} event sent to the workflow engine.
     *
     * <p>A failure is counted on the task, and the sweep carries on: one broken definition must not stop every other
     * deadline in the repository from being met. A task that is no longer open was closed by somebody else in the
     * meantime, which is not a failure.</p>
     *
     * @param resolver the sweep's session
     * @param timer the session the deadline is delivered through
     * @param path the overdue task
     */
    private void fire(final ResourceResolver resolver, final ResourceResolver timer, final String path)
    {
        if (!stillOpen(resolver, path)) {
            LOGGER.debug("The task {} was closed before its passed deadline was delivered", path);
            return;
        }
        try {
            timer.refresh();
            this.engine.receiveEvent(Objects.requireNonNull(timer.getResource(path), "The timer can see every task"),
                new WorkflowEvent(TaskCompletion.TIMEOUT_EVENT, Map.of()));
            LOGGER.debug("Delivered the passed deadline of {}", path);
        } catch (final WorkflowException | RuntimeException e) {
            if (stillOpen(resolver, path)) {
                LOGGER.error("Could not deliver the passed deadline of {}: {}", path, e.getMessage(), e);
                ErrorLogger.logError(e, ErrorContext.of(DueTimers.class, "deliver").about(path));
                countFailure(resolver, path);
            } else {
                LOGGER.debug("The task {} was closed while its passed deadline was being delivered", path);
            }
        }
    }

    /**
     * Whether a task is still waiting, as the repository has it now.
     *
     * @param resolver the sweep's session
     * @param path the task
     * @return {@code true} if it exists and is open
     */
    private static boolean stillOpen(final ResourceResolver resolver, final String path)
    {
        resolver.refresh();
        final Resource task = resolver.getResource(path);
        return task != null
            && TaskInstance.OPEN_STATUS.equals(task.getValueMap().get(STATUS_PROPERTY, String.class));
    }

    /**
     * Counts one more failed delivery on a task, in a commit of its own, and gives up on the task at the limit.
     *
     * @param resolver the sweep's session
     * @param path the task
     */
    private static void countFailure(final ResourceResolver resolver, final String path)
    {
        final ModifiableValueMap task = Objects.requireNonNull(
            Objects.requireNonNull(resolver.getResource(path), "An open task exists").adaptTo(ModifiableValueMap.class),
            "The engine's own session can write its tasks");
        final long failures = task.get(DELIVERY_FAILURES_PROPERTY, 0L) + 1;
        task.put(DELIVERY_FAILURES_PROPERTY, failures);
        try {
            resolver.commit();
        } catch (final PersistenceException e) {
            resolver.revert();
            LOGGER.error("Could not count a failed delivery of the deadline of {}: {}", path, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(DueTimers.class, "countFailure").about(path));
            return;
        }
        if (failures >= MAX_DELIVERY_FAILURES) {
            LOGGER.error("Gave up delivering the passed deadline of {} after {} failures", path, failures);
            ErrorLogger.logProblem("passed deadline abandoned after repeated failures",
                ErrorContext.of(DueTimers.class, "deliver").about(path).with(DELIVERY_FAILURES_PROPERTY, failures));
        }
    }
}
