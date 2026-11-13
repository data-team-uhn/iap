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
package io.uhndata.iap.tags.api;

import org.jetbrains.annotations.NotNull;

/**
 * Recomputes derived tags that have gone out of date.
 *
 * <p>
 * A commit recomputes the derived tags of the content it changes. Two things leave derived tags out of date anyway.
 * </p>
 *
 * <ul>
 * <li>A tag processor that fails leaves the values it could not compute as they were, and marks the node.</li>
 * <li>Deleting a tag definition, or changing whether the tag is inheritable or aggregated, changes what the copies
 * of that tag should be, but changes no content. No commit recomputes them. Until they are repaired,
 * {@code getEffectiveTagNames}, which reads the stored copies, and {@code hasTag}, which reads the definitions,
 * disagree about them.</li>
 * </ul>
 *
 * <p>
 * A repair computes nothing itself. It marks the affected nodes as stale and commits, and the propagation editor
 * recomputes them in full in that commit and removes the mark. A repair that finds nothing to do writes nothing.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TagRepairService
{
    /**
     * Repairs every node marked as stale, whatever the reason recorded on it. The mark is indexed, so this costs
     * little when there are none.
     *
     * @return what the repair did
     * @throws TagRepairException if the repair could not run, already logged and recorded with the reason
     */
    @NotNull
    RepairReport repairFailed() throws TagRepairException;

    /**
     * Repairs every node carrying a tag, in any of the four properties a tag name can appear in. Run it after
     * changing the tag's definition.
     *
     * @param tagName the name of the tag whose definition changed, as stored in a {@code tags} property, whatever
     *            characters it holds
     * @return what the repair did
     * @throws TagRepairException if the repair could not run, already logged and recorded with the reason
     */
    @NotNull
    RepairReport repair(@NotNull String tagName) throws TagRepairException;

    /**
     * What a repair did. A node that cannot be marked is counted and skipped, and the repair goes on with the rest.
     *
     * @param marked the number of nodes marked for recomputation
     * @param failed the number of nodes that could not be marked, already logged with the reason
     * @version $Id$
     * @since 0.1.0
     */
    record RepairReport(long marked, long failed)
    {
        /**
         * Whether the repair completed without leaving anything behind.
         *
         * @return {@code true} if nothing failed
         */
        public boolean isComplete()
        {
            return this.failed == 0;
        }
    }
}
