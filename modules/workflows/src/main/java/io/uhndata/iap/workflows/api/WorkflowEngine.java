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

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * The one door into the workflow machinery: every domain event, whatever channel it arrived by, is handed to
 * {@link #receiveEvent} and nothing changes workflow-managed state any other way. HTTP servlets, inbound email and
 * firing timers are all just translators building a {@link WorkflowEvent} and interpreting the
 * {@link WorkflowResult}.
 *
 * <p>Receiving an event means answering three questions in order, each with its own failure: is anything waiting
 * for this event on this target ({@link NoApplicableWorkflowException}), may this user fire it
 * ({@link NotAuthorizedException}), and is what it carries usable ({@link InvalidPayloadException})? Only then is
 * the transition executed — run to quiescence, all of its effects in a single commit, so an event either fully
 * happened or didn't happen at all.</p>
 *
 * <p>The current engine executes <em>system workflows</em>: the platform's own behavior, stored under
 * {@code /SystemWorkflows} and matched by the target's resource type. A system workflow runs entirely within the
 * request that fired the event and leaves no instance behind, which is why it must be straight-through — a
 * definition that would have to wait is rejected as broken rather than parked.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface WorkflowEngine
{
    /**
     * Receive one domain event aimed at one resource, and if it is acceptable, execute the transition it triggers.
     * The execution writes with the session behind the target resource — the firing user's own — so repository
     * access control is what authorizes it.
     *
     * @param target the resource the event is about, e.g. the homepage a creation was requested under
     * @param event the event itself
     * @return the variables the execution left behind, e.g. {@link WorkflowResult#CREATED_PATH}
     * @throws NoApplicableWorkflowException when nothing is waiting for this event on this target
     * @throws NotAuthorizedException when the firing user may not do this
     * @throws InvalidPayloadException when the event's data is missing something or violates a constraint
     * @throws WorkflowDefinitionException when the matched definition itself is broken
     * @throws WorkflowFailedException when execution fails for reasons outside definition and payload
     * @throws WorkflowException never directly, only as one of the above
     */
    @NotNull
    WorkflowResult receiveEvent(@NotNull Resource target, @NotNull WorkflowEvent event) throws WorkflowException;
}
