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
 * The outcome of a deletion or purge attempt.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class DeletionResult
{
    /**
     * What happened to the deletion request.
     *
     * @since 0.1.0
     */
    public enum Status
    {
        /** The impacted resources were moved into a new archive entry. */
        ARCHIVED,

        /** The impacted resources were permanently removed. */
        DELETED,

        /** A guard blocked the operation; see the impact's vetoes. */
        VETOED,

        /**
         * Other resources reference the impacted ones, and the deletion was not allowed to cascade over them; see
         * the impact's referrers. Nothing was changed.
         */
        REQUIRES_CONFIRMATION,

        /** The requesting user lacks the permission to remove an impacted resource. Nothing was changed. */
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
     * @param archiveEntryPath the path of the created archive entry, only for {@link Status#ARCHIVED}
     * @param impact the impact that was examined or executed
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
     * @param archiveEntryPath the path of the created archive entry, only for {@link Status#ARCHIVED}
     * @param impact the impact that was examined or executed
     * @param checkedOut the versionable nodes the deletion checked out, to check back in once committed
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
     * What happened to the deletion request.
     *
     * @return a status
     */
    @NotNull
    public Status getStatus()
    {
        return this.status;
    }

    /**
     * Where the deleted resources were archived.
     *
     * @return the path of the created archive entry, or {@code null} unless the status is {@link Status#ARCHIVED}
     */
    @Nullable
    public String getArchiveEntryPath()
    {
        return this.archiveEntryPath;
    }

    /**
     * The impact that was examined, and, when the status reports a change, executed.
     *
     * @return the impact
     */
    @NotNull
    public DeletionImpact getImpact()
    {
        return this.impact;
    }

    /**
     * The versionable nodes a deletion {@link DeletionService#deleteWithin within the caller's transaction} had to
     * check out, which the caller checks back in once it has committed.
     *
     * @return their paths; empty when the deletion committed itself, since it checked them back in already
     */
    @NotNull
    public List<String> getCheckedOut()
    {
        return this.checkedOut;
    }
}
