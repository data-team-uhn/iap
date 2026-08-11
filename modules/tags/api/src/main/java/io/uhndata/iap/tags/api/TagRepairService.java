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
 * Tags are normally maintained as content changes: whatever a commit touches has its derived tags recomputed before
 * the commit completes. Two things escape that, and this service exists for them.
 * </p>
 *
 * <ul>
 * <li>A tag processor that fails leaves the values it could not recompute as they were, and marks the node. Those
 * nodes are repaired routinely, since there are few of them and they are indexed.</li>
 * <li>Editing a tag <em>definition</em> — deleting it, or changing whether it is inheritable or aggregated —
 * invalidates copies of that tag anywhere in the repository, without changing any of the content carrying them. No
 * commit touches those nodes, so nothing recomputes them; until they are repaired, {@code getEffectiveTagNames}
 * (which reads the stored values) and {@code hasTag} (which filters by definition) disagree about them, permanently
 * and in opposite directions.</li>
 * </ul>
 *
 * <p>
 * Repair does not compute anything itself. It marks the affected nodes as stale and commits, and the propagation
 * editor — the one piece that knows the phase order, the entity scopes and the failure policy — recomputes them in
 * full and clears the mark. A repair that finds nothing to do writes nothing.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TagRepairService
{
    /**
     * Repairs every node whose derived tags could not be computed. Cheap enough to run on a schedule: the marker is
     * indexed, and a healthy repository has no such nodes.
     *
     * @return what the repair did
     */
    @NotNull
    RepairReport repairFailed();

    /**
     * Repairs every node carrying a tag, in any of the four properties a tag name can appear in. This is the repair
     * to run after a definition changed, naming the tag that changed; it is bounded by how widely that one tag is
     * used rather than by the size of the repository.
     *
     * @param tagName the name of the tag whose definition changed
     * @return what the repair did
     */
    @NotNull
    RepairReport repair(@NotNull String tagName);

    /**
     * What a repair did. A repair is best-effort: it reports what it could not do rather than failing, since the
     * alternative — abandoning the rest of the repository because one node is unwritable — leaves more stale tags
     * behind than it fixes.
     *
     * @param marked the number of nodes marked for recomputation
     * @param failed the number of nodes that could not be marked, already logged with their reason
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
