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
package io.uhndata.iap.workflows.api;

import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * The one door into the workflow machinery. Every domain event is handed to {@link #receiveEvent}, and nothing
 * changes workflow-managed state any other way. HTTP servlets, inbound email and firing timers are translators:
 * they build a {@link WorkflowEvent} and read the {@link WorkflowResult}.
 *
 * <p>Receiving an event answers three questions in order, each with its own failure. Is anything waiting for this
 * event on this target ({@link NoApplicableWorkflowException}); may this user fire it
 * ({@link NotAuthorizedException}); is what it carries usable ({@link InvalidPayloadException}). Only then does
 * the transition run, to quiescence, with every effect in one commit. An event either fully happened or did not
 * happen.</p>
 *
 * <p>The current engine executes system workflows: the platform's own behaviour, stored under
 * {@code /SystemWorkflows} and matched by the target's resource type. Such a workflow runs inside the request
 * that fired the event and leaves no instance behind, so it must be straight-through. A definition that would
 * have to wait is rejected as broken rather than parked.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface WorkflowEngine
{
    /**
     * Receive one domain event aimed at one resource, and execute the transition it triggers if it is
     * acceptable. The execution writes with the session behind the target resource, which is the firing user's
     * own, so repository access control is what authorizes it.
     *
     * @param target the resource the event is about, e.g. the homepage a creation was requested under
     * @param event the event itself
     * @return the variables the execution left behind, e.g. {@link WorkflowResult#CREATED_PATH_VARIABLE}
     * @throws NoApplicableWorkflowException when nothing is waiting for this event on this target
     * @throws NotAuthorizedException when the firing user may not do this
     * @throws InvalidPayloadException when the event's data is missing something or violates a constraint
     * @throws WorkflowDefinitionException when the matched definition itself is broken
     * @throws WorkflowFailedException when execution fails for reasons outside definition and payload
     * @throws WorkflowException never directly, only as one of the above
     */
    @NotNull
    WorkflowResult receiveEvent(@NotNull Resource target, @NotNull WorkflowEvent event) throws WorkflowException;

    /**
     * The events the asking user could send to one resource right now: those a workflow waits for in the
     * resource's current state, and whose definition admits that user. What the frontend offers is decided by
     * this rather than by a copy of the workflows' rules. An available event is one the engine would accept, not
     * one guaranteed to succeed: its payload and the steps it runs may still refuse it.
     *
     * @param target the resource events would be aimed at, resolved through the asking user's own session
     * @return the event names, in alphabetical order; empty when nothing is available
     * @throws WorkflowDefinitionException when several system workflows would take one of the events, which
     *             receiving it would refuse
     * @throws WorkflowFailedException when the engine cannot establish who is asking
     * @throws WorkflowException never directly, only as one of the above
     */
    @NotNull
    Set<String> getAvailableEvents(@NotNull Resource target) throws WorkflowException;

    /**
     * The system workflow that would handle an event the asking user sent to one resource right now: the one whose
     * start event catches it, whose guard holds in the resource's current state, and whose definition admits that
     * user, found as {@link #receiveEvent} would find it but without sending anything. Reading it tells what sending
     * the event would do, e.g. what a create would make. Everyone may read the system workflows, so the version
     * comes back as the asking user's own session reads it, and lasts as long as that session.
     *
     * @param target the resource the event would be aimed at, resolved through the asking user's own session
     * @param event the event's name
     * @return the workflow version, or {@code null} if no system workflow would take the event from this user;
     *         always {@code null} for a task, whose events carry its instance on instead
     * @throws WorkflowDefinitionException when several system workflows would take the event
     * @throws WorkflowFailedException when the engine cannot establish who is asking, or the asking session cannot
     *             read the system workflows
     * @throws WorkflowException never directly, only as one of the above
     */
    @Nullable
    WorkflowVersion findApplicableWorkflow(@NotNull Resource target, @NotNull String event) throws WorkflowException;
}
