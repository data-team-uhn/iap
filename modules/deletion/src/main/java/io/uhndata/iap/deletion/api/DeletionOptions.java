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
 * How to delete: whether to delete the resources that reference the deleted one too, whether to skip the archive,
 * and whom the archive records as having deleted it.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class DeletionOptions
{
    /** For {@link #of(boolean, boolean)}: resources referencing the deleted one are deleted along with it. */
    public static final boolean DELETE_RECURSIVELY = true;

    /** For {@link #of(boolean, boolean)}: resources referencing the deleted one block the deletion. */
    public static final boolean REFUSE_IF_REFERENCED = false;

    /** For {@link #of(boolean, boolean)}: the deleted resources are removed for good. */
    public static final boolean DELETE_PERMANENTLY = true;

    /** For {@link #of(boolean, boolean)}: the deleted resources are moved into the archive. */
    public static final boolean MOVE_TO_ARCHIVE = false;

    private final boolean recursive;

    private final boolean permanent;

    private final String onBehalfOf;

    private DeletionOptions(final boolean recursive, final boolean permanent, final String onBehalfOf)
    {
        this.recursive = recursive;
        this.permanent = permanent;
        this.onBehalfOf = onBehalfOf;
    }

    /**
     * The default options: refuse if other resources reference the deleted one, and move it into the archive.
     *
     * @return {@link #REFUSE_IF_REFERENCED} and {@link #MOVE_TO_ARCHIVE}
     */
    @NotNull
    public static DeletionOptions recoverable()
    {
        return new DeletionOptions(REFUSE_IF_REFERENCED, MOVE_TO_ARCHIVE, null);
    }

    /**
     * The given options, written with the named flags, such as {@code of(REFUSE_IF_REFERENCED, MOVE_TO_ARCHIVE)}.
     *
     * @param recursive {@link #DELETE_RECURSIVELY} to delete the referencing resources too, or
     *            {@link #REFUSE_IF_REFERENCED} to refuse
     * @param permanent {@link #DELETE_PERMANENTLY} to remove the deleted resources for good, or
     *            {@link #MOVE_TO_ARCHIVE} to archive them
     * @return the options
     */
    @NotNull
    public static DeletionOptions of(final boolean recursive, final boolean permanent)
    {
        return new DeletionOptions(recursive, permanent, null);
    }

    /**
     * Whether the resources that reference the deleted one are deleted too, rather than blocking the deletion.
     *
     * @return {@code true} to delete them too
     */
    public boolean isRecursive()
    {
        return this.recursive;
    }

    /**
     * Whether the deleted resources are removed for good, rather than moved into the archive.
     *
     * @return {@code true} to remove them for good
     */
    public boolean isPermanent()
    {
        return this.permanent;
    }

    /**
     * The same options, for a deletion a privileged session makes for a user, such as a workflow's. The archive
     * records that user as having deleted the resources, rather than the session's own user.
     *
     * @param userId the user id of the user the deletion is made for
     * @return the new options
     */
    @NotNull
    public DeletionOptions onBehalfOf(@NotNull final String userId)
    {
        return new DeletionOptions(this.recursive, this.permanent, userId);
    }

    /**
     * The user the deletion is made for, if not the requesting session's own user.
     *
     * @return a user id; {@code null} if the deletion is made for the requesting session's user
     */
    @Nullable
    public String getOnBehalfOf()
    {
        return this.onBehalfOf;
    }
}
