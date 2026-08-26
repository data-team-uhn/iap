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
 * The event was expected, the user was entitled to fire it and what it carried was usable, but the thing it is
 * aimed at is not in a state that admits it — promoting a version that has already been retired, or drafting a
 * label some other version already carries. HTTP channels answer 409.
 *
 * <p>Distinct from {@link NoApplicableWorkflowException}, which answers the same status for the neighbouring
 * question: that one says nothing was waiting for this event at all, this one that something was and the target
 * is the wrong shape for it. A client can act on the difference — the first is a stale UI offering a button that
 * does not exist here, the second a stale UI offering one whose moment has passed.</p>
 *
 * <p>Distinct from {@link InvalidPayloadException} for the same reason a 409 is not a 400: nothing about the
 * request would be improved by sending it differently. What has to change is the target, or the request has to
 * be abandoned.</p>
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
