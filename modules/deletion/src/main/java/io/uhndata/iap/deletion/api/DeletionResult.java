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

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The outcome of a deletion or a purge.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class DeletionResult
{
    /**
     * What happened to a deletion or a purge.
     *
     * @since 0.1.0
     */
    public enum Status
    {
        /** The resources were moved into a new archive entry. */
        ARCHIVED,

        /** The resources, or the purged archive entry, were removed for good. */
        DELETED,

        /** A guard refused, and nothing changed. The impact's vetoes say which and why. */
        VETOED,

        /**
         * Other resources reference the deleted ones, and the options did not allow deleting them too. Nothing
         * changed. The impact's referrers say which.
         */
        REQUIRES_CONFIRMATION,

        /** The requesting user may not remove one of the resources to delete. Nothing changed. */
        DENIED
    }

    private final Status status;

    private final String archiveEntryPath;

    private final DeletionImpact impact;

    private final List<String> checkedOut;

    /**
     * Record the outcome of a deletion that committed, or changed nothing.
     *
     * @param status what happened
     * @param archiveEntryPath the path of the new archive entry; {@code null} unless the status is
     *            {@link Status#ARCHIVED}
     * @param impact what the deletion removed, or would have removed, and what blocked it
     */
    public DeletionResult(@NotNull final Status status, @Nullable final String archiveEntryPath,
        @NotNull final DeletionImpact impact)
    {
        this(status, archiveEntryPath, impact, List.of());
    }

    /**
     * Record the outcome of a deletion left for its caller to commit.
     *
     * @param status what happened
     * @param archiveEntryPath the path the new archive entry has once committed; {@code null} unless the status is
     *            {@link Status#ARCHIVED}
     * @param impact what the deletion removed, or would have removed, and what blocked it
     * @param checkedOut the paths of the versionable nodes the deletion checked out, to check back in after the
     *            commit
     */
    public DeletionResult(@NotNull final Status status, @Nullable final String archiveEntryPath,
        @NotNull final DeletionImpact impact, @NotNull final List<String> checkedOut)
    {
        this.status = status;
        this.archiveEntryPath = archiveEntryPath;
        this.impact = impact;
        this.checkedOut = List.copyOf(checkedOut);
    }

    /**
     * What happened.
     *
     * @return the status
     */
    @NotNull
    public Status getStatus()
    {
        return this.status;
    }

    /**
     * Where the deleted resources were archived.
     *
     * @return the path of the new archive entry; {@code null} unless the status is {@link Status#ARCHIVED}
     */
    @Nullable
    public String getArchiveEntryPath()
    {
        return this.archiveEntryPath;
    }

    /**
     * What the deletion removed, or would have removed, and what blocked it.
     *
     * @return the impact; carried out only if the status is {@link Status#ARCHIVED} or {@link Status#DELETED}
     */
    @NotNull
    public DeletionImpact getImpact()
    {
        return this.impact;
    }

    /**
     * The versionable nodes a deletion {@link DeletionService#deleteWithin within the caller's transaction} checked
     * out, for the caller to check back in after its commit.
     *
     * @return their paths; empty if the deletion committed by itself and checked them back in, or checked none out
     */
    @NotNull
    public List<String> getCheckedOut()
    {
        return this.checkedOut;
    }
}
