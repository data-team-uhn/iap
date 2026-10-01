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

import org.jetbrains.annotations.NotNull;

/**
 * The event was expected, valid, and usable, but its target is not in a state that admits it — e.g. promoting an
 * already-retired version, or drafting a label another version already carries. HTTP channels answer 409.
 *
 * <p>Distinct from {@link NoApplicableWorkflowException} (also 409): that one means nothing was waiting for the
 * event at all, while this one means something was, but the target's shape was wrong for it. To a client, that's
 * the difference between a stale UI offering a button that doesn't exist, and one offering a button whose moment
 * has passed.</p>
 *
 * <p>Distinct from {@link InvalidPayloadException} for the same reason 409 isn't 400: resending the request
 * differently wouldn't help. What has to change is the target, or the request must be abandoned.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class WorkflowConflictException extends WorkflowException
{
    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     *
     * @param message what went wrong, phrased for the person or channel that fired the event
     */
    public WorkflowConflictException(@NotNull final String message)
    {
        super(message);
    }
}
