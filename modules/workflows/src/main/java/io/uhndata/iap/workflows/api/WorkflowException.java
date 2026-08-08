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
 * The base of everything that can go wrong while receiving an event. The subtypes distinguish the three layers an
 * event is judged on — state, authorization, payload — plus failures of the definitions and of the machinery
 * itself, because each layer deserves a different answer: a channel like HTTP maps them to 409, 403, 400 and 500
 * respectively.
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class WorkflowException extends Exception
{
    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     *
     * @param message what went wrong, phrased for the person or channel that fired the event
     */
    protected WorkflowException(@NotNull final String message)
    {
        super(message);
    }

    /**
     * Constructor.
     *
     * @param message what went wrong, phrased for the person or channel that fired the event
     * @param cause the underlying failure
     */
    protected WorkflowException(@NotNull final String message, @NotNull final Throwable cause)
    {
        super(message, cause);
    }
}
