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

import java.util.Map;
import java.util.function.Supplier;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;

/**
 * Delivers an event to its target in one commit, and runs it again when another commit to the same entity won a
 * race. Every event the engine handles goes through here, whether it runs a system workflow or resumes a task.
 *
 * <p>Each attempt reads the target afresh and has a dispatcher of its own. A lost attempt is reverted, which also
 * brings its session up to date, and the notifications it announced and the work it left are dropped with its
 * writes. What it checked out is checked back in, since a checkout survives a revert. The attempt that commits
 * marks each entity it changed, then finishes what waited for its commit.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EventDelivery
{
    /** Hands out a fresh dispatcher for each attempt. */
    private final Supplier<ServiceTaskDispatcher> dispatchers;

    /** Commits each attempt so that the commit records it in the history. */
    private final DeliveryRecorder recorder;

    /**
     * Constructor.
     *
     * @param dispatchers hands out a fresh dispatcher for each attempt
     * @param recorder commits each attempt so that the commit records it in the history
     */
    EventDelivery(final Supplier<ServiceTaskDispatcher> dispatchers, final DeliveryRecorder recorder)
    {
        this.dispatchers = dispatchers;
        this.recorder = recorder;
    }

    /**
     * Delivers an event, running it again after each lost race until it commits or the retries run out.
     *
     * @param target the resource the event is aimed at, resolved through the engine's session
     * @param retries how many times to run it again after a lost race
     * @param run what the event does to its target
     * @return the variables the run that committed left behind
     * @throws WorkflowException when the run cannot complete, typed by whose fault that is
     */
    WorkflowResult deliver(final Resource target, final int retries, final Run run) throws WorkflowException
    {
        return RepositoryFailures.retryingConflicts(retries,
            () -> attempt(target.getResourceResolver(), target.getPath(), run));
    }

    /**
     * Makes one attempt at the delivery, and commits it.
     *
     * @param resolver the engine's session
     * @param path the path of the resource the event is aimed at
     * @param run what the event does to its target
     * @return the variables the run left behind
     * @throws WorkflowException when the attempt cannot complete, typed by whose fault that is
     */
    private WorkflowResult attempt(final ResourceResolver resolver, final String path, final Run run)
        throws WorkflowException
    {
        final ServiceTaskDispatcher dispatcher = this.dispatchers.get();
        final Outcome outcome;
        try {
            final Resource current = resolver.getResource(path);
            if (current == null) {
                throw RepositoryFailures.deletedMeanwhile(path);
            }
            dispatcher.consequences().noteTarget(path);
            outcome = run.apply(current, dispatcher);
            EntityRevision.stamp(resolver, dispatcher.consequences().changedPaths());
            this.recorder.commit(resolver, outcome.action());
        } catch (final PersistenceException e) {
            abandon(resolver, dispatcher);
            throw RepositoryFailures.translate(e);
        } catch (final WorkflowException | RuntimeException e) {
            abandon(resolver, dispatcher);
            throw e;
        }
        dispatcher.completeAfterCommit(resolver);
        return new WorkflowResult(outcome.variables());
    }

    /**
     * Discards whatever an aborted attempt had already changed. A failing event leaves no trace.
     *
     * @param resolver the engine's session
     * @param dispatcher the attempt's dispatcher, which knows what its steps checked out
     */
    private static void abandon(final ResourceResolver resolver, final ServiceTaskDispatcher dispatcher)
    {
        if (resolver.hasChanges()) {
            resolver.revert();
        }
        dispatcher.consequences().abandon(resolver);
    }

    /**
     * What an event does to its target, without committing.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    interface Run
    {
        /**
         * Runs the event on its target.
         *
         * @param target the resource the event is aimed at, as this attempt reads it
         * @param dispatcher what performs this attempt's service tasks and holds its notifications
         * @return the variables it left behind, and the delivery as its history records it
         * @throws WorkflowException when the run cannot complete
         * @throws PersistenceException when a write fails
         */
        Outcome apply(Resource target, ServiceTaskDispatcher dispatcher) throws WorkflowException, PersistenceException;
    }

    /**
     * What a run left behind, and the delivery as its history records it.
     *
     * @param variables the variables the run left behind; empty if it leaves none
     * @param action the delivery as its history records it
     * @version $Id$
     * @since 0.1.0
     */
    record Outcome(Map<String, Object> variables, RecordedAction action)
    {
    }
}
