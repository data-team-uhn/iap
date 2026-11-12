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
package io.uhndata.iap.deletion.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A deletion, restore or purge failed. The repository failed, the {@code iap-deletion} service user is not
 * available, or Oak refused the commit over a reference the analysis missed. A refusal is not a failure: vetoes,
 * blocking referrers and missing permissions come back in the operation's result.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class DeletionException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /**
     * Report a failure.
     *
     * @param message what failed
     * @param cause the error that caused it; {@code null} if nothing threw
     */
    public DeletionException(@NotNull final String message, @Nullable final Throwable cause)
    {
        super(message, cause);
    }
}
