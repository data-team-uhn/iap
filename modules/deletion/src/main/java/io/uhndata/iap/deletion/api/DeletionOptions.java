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
 * How a deletion should behave: whether it may cascade over resources referencing the deleted one, and whether the
 * deleted resources should skip the archive and be removed permanently.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class DeletionOptions
{
    private final boolean recursive;

    private final boolean permanent;

    private DeletionOptions(final boolean recursive, final boolean permanent)
    {
        this.recursive = recursive;
        this.permanent = permanent;
    }

    /**
     * The default options: refuse if resources other than links reference the deleted one, and move the deleted
     * resources into the archive.
     *
     * @return non-recursive, non-permanent options
     */
    @NotNull
    public static DeletionOptions recoverable()
    {
        return new DeletionOptions(false, false);
    }

    /**
     * Explicit options.
     *
     * @param recursive whether resources referencing the deleted one are deleted along with it instead of blocking
     *            the deletion
     * @param permanent whether the deleted resources are removed for good instead of being moved into the archive
     * @return options with the requested behavior
     */
    @NotNull
    public static DeletionOptions of(final boolean recursive, final boolean permanent)
    {
        return new DeletionOptions(recursive, permanent);
    }

    /**
     * Whether resources referencing the deleted one are deleted along with it instead of blocking the deletion.
     *
     * @return {@code true} if the deletion cascades
     */
    public boolean isRecursive()
    {
        return this.recursive;
    }

    /**
     * Whether the deleted resources are removed for good instead of being moved into the archive.
     *
     * @return {@code true} if the deletion is permanent
     */
    public boolean isPermanent()
    {
        return this.permanent;
    }
}
