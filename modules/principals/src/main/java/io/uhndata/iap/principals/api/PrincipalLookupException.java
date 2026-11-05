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
package io.uhndata.iap.principals.api;

import org.jetbrains.annotations.NotNull;

/**
 * The repository could not say who somebody is or what they belong to: the session has no user store, or reading
 * it failed.
 *
 * <p>A name the repository does not know is not a failure. It names nobody, and {@link PrincipalService#isOneOf}
 * answers {@code false} for it. Whether a failure refuses an actor or fails the request is for the caller to
 * decide.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class PrincipalLookupException extends RuntimeException
{
    private static final long serialVersionUID = -5088217878432090418L;

    /**
     * Constructor.
     *
     * @param message what could not be answered
     * @param cause the repository's exception; {@code null} if the session has no user store
     */
    public PrincipalLookupException(@NotNull final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
