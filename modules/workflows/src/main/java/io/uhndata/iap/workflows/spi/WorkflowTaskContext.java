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
package io.uhndata.iap.workflows.spi;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * Everything a {@link ServiceTaskHandler} gets to work with: what the event was about, what it carried, how this
 * particular activity is configured, and where to leave results for the nodes downstream.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface WorkflowTaskContext
{
    /**
     * The resource the triggering event was aimed at, e.g. the homepage a creation was requested under.
     *
     * @return a resource, backed by the engine's own session
     */
    @NotNull
    Resource getTarget();

    /**
     * Who this execution is acting for: the user who fired the event, as their repository user id.
     *
     * <p>The handler is not running with that user's rights. The engine already decided they were allowed here
     * and does the work privileged, so this is who to record and who to decide about. It is not a permission
     * check.</p>
     *
     * @return the firing user's id
     */
    @NotNull
    String getActor();

    /**
     * The event that set the workflow in motion.
     *
     * @return the triggering event
     */
    @NotNull
    WorkflowEvent getEvent();

    /**
     * The activity being performed, whose properties are the handler's configuration. A handler reads what it
     * needs from here rather than being parameterized in code, so one handler can serve many workflows.
     *
     * @return the activity node of the workflow definition
     */
    @NotNull
    Activity getActivity();

    /**
     * One variable of this execution: what an earlier node left behind.
     *
     * @param name the variable name
     * @return the value, or {@code null} if nothing set it
     */
    @Nullable
    Object getVariable(@NotNull String name);

    /**
     * Leaves a variable behind, for the nodes downstream and for the channel that fired the event, e.g.
     * {@link io.uhndata.iap.workflows.api.WorkflowResult#CREATED_PATH_VARIABLE}. Variables are shared by the whole
     * execution: later nodes overwrite earlier values.
     *
     * @param name the variable name
     * @param value the value to record, possibly {@code null}
     */
    void setVariable(@NotNull String name, @Nullable Object value);

    /**
     * The session to read and write repository content with.
     *
     * <p>It is the engine's own, and it is privileged. The repository will not stop a handler from doing
     * anything, because what the firing user may do was decided from the definition before the handler ran. A
     * handler that wants to treat something as invisible or forbidden has to say so itself.</p>
     *
     * <p>The engine owns the session. Handlers must not commit, revert or close it: the whole execution lands in
     * one commit at quiescence.</p>
     *
     * @return the engine's resource resolver
     */
    @NotNull
    ResourceResolver getResourceResolver();

    /**
     * Sends an event on to another resource, as part of this execution, and waits for the workflow it starts to finish.
     * The event is matched, guarded and authorized exactly as if the user had sent it themselves, triggering a system
     * workflow if everything is correct. The called workflow runs inside the calling one, in the same JCR session and
     * the same commit, so either both happen or neither does.
     *
     * @param target the resource the event is sent to, backed by the engine's own session
     * @param event the event to send
     * @throws WorkflowException when the event is refused or its workflow fails
     * @throws PersistenceException when its workflow's writes fail
     */
    void sendEvent(@NotNull Resource target, @NotNull WorkflowEvent event)
        throws WorkflowException, PersistenceException;

    /**
     * Starts an instance of a workflow on a resource, as part of this execution, and runs it up to its first
     * wait. The instance acts for the same actor.
     *
     * @param host the resource the workflow drives, which must be {@code wf:WorkflowAttachable}
     * @param version the workflow version to start
     * @throws WorkflowException when the version is not active, the resource cannot hold workflows, or the
     *             definition cannot be run
     * @throws PersistenceException when the instance cannot be written
     */
    void startWorkflow(@NotNull Resource host, @NotNull WorkflowVersion version)
        throws WorkflowException, PersistenceException;

    /**
     * Starts an instance of a workflow on a resource, optionally cancelling the instance of the same workflow the
     * resource already runs, so that a reading can be started over.
     *
     * @param host the resource the workflow drives, which must be {@code wf:WorkflowAttachable}
     * @param version the workflow version to start
     * @param replaceActive whether to cancel an active instance of this workflow on the resource first
     * @throws WorkflowException when the version is not active, the resource cannot hold workflows, or the
     *             definition cannot be run
     * @throws PersistenceException when the instance cannot be written
     */
    default void startWorkflow(@NotNull Resource host, @NotNull WorkflowVersion version, boolean replaceActive)
        throws WorkflowException, PersistenceException
    {
        if (replaceActive) {
            throw new UnsupportedOperationException("This context cannot replace an active instance");
        }
        startWorkflow(host, version);
    }
}
