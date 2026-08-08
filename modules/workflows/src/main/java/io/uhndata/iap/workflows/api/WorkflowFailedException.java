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
 * The event was acceptable but executing it failed for reasons outside the definition and the payload — the
 * repository refused the commit, the engine's own service user is missing. HTTP channels answer 500.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class WorkflowFailedException extends WorkflowException
{
    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     *
     * @param message what went wrong, phrased for the person or channel that fired the event
     */
    public WorkflowFailedException(@NotNull final String message)
    {
        super(message);
    }

    /**
     * Constructor.
     *
     * @param message what went wrong, phrased for the person or channel that fired the event
     * @param cause the underlying failure
     */
    public WorkflowFailedException(@NotNull final String message, @NotNull final Throwable cause)
    {
        super(message, cause);
    }
}
