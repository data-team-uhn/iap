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
public interface WorkflowTaskContext extends Execution
{
    /**
     * Who this execution is acting for, as their repository user id: the user who fired the event, or the user
     * they fired it on behalf of.
     *
     * <p>The handler is not running with that user's rights. The engine already decided they were allowed here
     * and does the work privileged, so this is who to record and who to decide about. It is not a permission
     * check.</p>
     *
     * @return the id of the user the execution counts as
     */
    @NotNull
    String getActor();

    /**
     * Who actually sent the event, as their repository user id. The same as {@link #getActor()}, unless they sent
     * it on behalf of someone else.
     *
     * @return the id of the user who sent the event
     */
    @NotNull
    default String getSender()
    {
        return getActor();
    }

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
     * Leaves a variable behind, for the nodes downstream and for the channel that fired the event, e.g.
     * {@link io.uhndata.iap.workflows.api.WorkflowResult#CREATED_PATH_VARIABLE}. Variables are shared by the whole
     * execution: later nodes overwrite earlier values.
     *
     * @param name the variable name
     * @param value the value to record, possibly {@code null}
     */
    void setVariable(@NotNull String name, @Nullable Object value);

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
     * Starts an instance of a workflow on a resource as {@link #startWorkflow} does, after cancelling every instance
     * of the same workflow still active on it, whatever version it runs. The cancelled instances stay on the
     * resource with their tasks cancelled, as the record of what came before.
     *
     * @param host the resource the workflow drives, which must be {@code wf:WorkflowAttachable}
     * @param version the workflow version to start
     * @throws WorkflowException when the version is not active, the resource cannot hold workflows, or the
     *             definition cannot be run
     * @throws PersistenceException when an instance cannot be written
     */
    void replaceWorkflow(@NotNull Resource host, @NotNull WorkflowVersion version)
        throws WorkflowException, PersistenceException;

    /**
     * Says what this step did to a resource, for the history: the part the resource played, named by its purpose.
     * The history records what changed on its own; only the meaning of the change has to be said.
     *
     * @param resource the resource; a part of an entity stands for the entity it belongs to
     * @param role a word for the part it played, in the vocabulary of the workflow rather than of the storage, such
     *            as {@code submitted}, {@code retired} or {@code activated}
     */
    void recordRole(@NotNull Resource resource, @NotNull String role);

    /**
     * Asks for a versionable node to be checked back in once this delivery has committed. A step that had to check
     * a node out cannot check it back in itself. A check-in refuses pending changes, and the delivery's changes stay
     * pending until the engine commits them all at once.
     *
     * @param path the absolute path of the versionable node
     */
    void checkInAfterCommit(@NotNull String path);
}
