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

/**
 * One reason an archive entry could not be restored: which archived item cannot go back, and why.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class RestoreConflict
{
    /**
     * Why an archived item cannot be restored to its original path.
     *
     * @since 0.1.0
     */
    public enum Reason
    {
        /** The original parent no longer exists, so there is nowhere to restore to. */
        PARENT_MISSING,

        /** Another resource now occupies the original path. */
        OCCUPIED,

        /** The requesting user is not allowed to create the resource back at its original path. */
        NO_RIGHTS
    }

    private final String originalPath;

    private final Reason reason;

    /**
     * Record a conflict.
     *
     * @param originalPath the path the archived item should be restored to
     * @param reason why it cannot be
     */
    public RestoreConflict(@NotNull final String originalPath, @NotNull final Reason reason)
    {
        this.originalPath = originalPath;
        this.reason = reason;
    }

    /**
     * The path the archived item should be restored to.
     *
     * @return an absolute path
     */
    @NotNull
    public String getOriginalPath()
    {
        return this.originalPath;
    }

    /**
     * Why the item cannot be restored.
     *
     * @return a reason
     */
    @NotNull
    public Reason getReason()
    {
        return this.reason;
    }
}
