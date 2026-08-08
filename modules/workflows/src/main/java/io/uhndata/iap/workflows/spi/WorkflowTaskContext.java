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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;

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
     * Who this execution is acting for: the user who fired the event, as their repository user id. Note that the
     * handler is <em>not</em> running with that user's rights — the engine already decided they were allowed here,
     * and does the work privileged — so this is who to record and who to make decisions about, never a substitute
     * for a permission check.
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
     * The activity being performed, whose properties are the handler's configuration — a handler reads what it
     * needs from here rather than being parameterized in code, so the same handler can serve many workflows.
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
     * {@link io.uhndata.iap.workflows.api.WorkflowResult#CREATED_PATH}. Variables are shared by the whole
     * execution: later nodes overwrite earlier values.
     *
     * @param name the variable name
     * @param value the value to record, possibly {@code null}
     */
    void setVariable(@NotNull String name, @Nullable Object value);

    /**
     * The session to read and write repository content with. It is the <em>engine's own</em>, and it is
     * privileged: the repository will not stop a handler from doing anything, because the decision about what the
     * firing user may do was already taken, from the definition, before this handler ran. A handler that wants to
     * treat something as invisible or forbidden has to say so itself — access control will not say it for them.
     * The engine owns the session's lifecycle: handlers must not commit, revert or close it, since the whole
     * execution lands in one commit at quiescence.
     *
     * @return the engine's resource resolver
     */
    @NotNull
    ResourceResolver getResourceResolver();
}
