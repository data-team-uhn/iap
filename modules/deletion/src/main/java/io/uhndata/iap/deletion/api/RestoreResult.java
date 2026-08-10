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

/**
 * The outcome of restoring an archive entry. The restore is all-or-nothing: either every archived item went back to
 * its original path and the entry is gone, or nothing was changed and every conflict is reported.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class RestoreResult
{
    /**
     * What happened to the restore request.
     *
     * @since 0.1.0
     */
    public enum Status
    {
        /** Every archived item was moved back to its original path, and the emptied entry was removed. */
        RESTORED,

        /** At least one archived item could not be restored; nothing was changed. */
        CONFLICT
    }

    private final Status status;

    private final List<String> restoredPaths;

    private final List<RestoreConflict> conflicts;

    /**
     * Record an outcome.
     *
     * @param status what happened
     * @param restoredPaths the paths the archived items were restored to, empty unless successful
     * @param conflicts the conflicts that blocked the restore, empty when successful
     */
    public RestoreResult(@NotNull final Status status, @NotNull final List<String> restoredPaths,
        @NotNull final List<RestoreConflict> conflicts)
    {
        this.status = status;
        this.restoredPaths = List.copyOf(restoredPaths);
        this.conflicts = List.copyOf(conflicts);
    }

    /**
     * What happened to the restore request.
     *
     * @return a status
     */
    @NotNull
    public Status getStatus()
    {
        return this.status;
    }

    /**
     * Where the archived items were restored to.
     *
     * @return an immutable list of absolute paths, empty unless the status is {@link Status#RESTORED}
     */
    @NotNull
    public List<String> getRestoredPaths()
    {
        return this.restoredPaths;
    }

    /**
     * What blocked the restore.
     *
     * @return an immutable list of conflicts, empty when the status is {@link Status#RESTORED}
     */
    @NotNull
    public List<RestoreConflict> getConflicts()
    {
        return this.conflicts;
    }
}
