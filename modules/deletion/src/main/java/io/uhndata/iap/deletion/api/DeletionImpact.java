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
 * The complete impact of a deletion: what would be deleted, which links would be removed, and what blocks the
 * operation, if anything.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class DeletionImpact
{
    private final List<String> itemPaths;

    private final List<String> removedLinkPaths;

    private final List<Veto> vetoes;

    private final List<ReferrerGroup> referrers;

    private final long inaccessibleReferrerCount;

    private final String summary;

    /**
     * Record an impact.
     *
     * @param itemPaths the distinct subtrees that would be deleted
     * @param removedLinkPaths the link nodes that would be removed from remaining resources
     * @param vetoes the vetoes blocking the deletion
     * @param referrers the referencing resources blocking a non-recursive deletion, grouped by type
     * @param inaccessibleReferrerCount the number of blocking resources withheld from {@code referrers} because the
     *            requesting user cannot see them
     * @param summary a human-readable description of the blockers, empty when there are none
     */
    public DeletionImpact(@NotNull final List<String> itemPaths, @NotNull final List<String> removedLinkPaths,
        @NotNull final List<Veto> vetoes, @NotNull final List<ReferrerGroup> referrers,
        final long inaccessibleReferrerCount, @NotNull final String summary)
    {
        this.itemPaths = List.copyOf(itemPaths);
        this.removedLinkPaths = List.copyOf(removedLinkPaths);
        this.vetoes = List.copyOf(vetoes);
        this.referrers = List.copyOf(referrers);
        this.inaccessibleReferrerCount = inaccessibleReferrerCount;
        this.summary = summary;
    }

    /**
     * The distinct subtrees that would be deleted: the requested resource itself, plus any resources dragged along
     * by links or, for a recursive deletion, by references. No path in the list is an ancestor of another.
     *
     * @return an immutable list of absolute paths
     */
    @NotNull
    public List<String> getItemPaths()
    {
        return this.itemPaths;
    }

    /**
     * The link nodes that would be removed from resources which themselves remain in place, because their
     * definition's deletion policy asks for the link, and only the link, to go away with its target.
     *
     * @return an immutable list of absolute paths
     */
    @NotNull
    public List<String> getRemovedLinkPaths()
    {
        return this.removedLinkPaths;
    }

    /**
     * The vetoes blocking the deletion. A single veto anywhere blocks the whole operation.
     *
     * @return an immutable list, empty when no guard objected
     */
    @NotNull
    public List<Veto> getVetoes()
    {
        return this.vetoes;
    }

    /**
     * The resources blocking a non-recursive deletion because they reference a resource that would be deleted,
     * grouped by their primary type. Empty for a recursive deletion, which deletes referencing resources instead
     * of being blocked by them. Resources the requesting user cannot see are counted in
     * {@link #getInaccessibleReferrerCount()} instead of being listed here.
     *
     * @return an immutable list of groups
     */
    @NotNull
    public List<ReferrerGroup> getReferrers()
    {
        return this.referrers;
    }

    /**
     * The number of blocking resources withheld from {@link #getReferrers()} because the requesting user cannot
     * see them. This includes referencing resources hidden by access control, and, for a permanent deletion,
     * archived resources referencing the deleted one, which are never deleted as a side effect.
     *
     * @return a number, {@code 0} when everything relevant is visible
     */
    public long getInaccessibleReferrerCount()
    {
        return this.inaccessibleReferrerCount;
    }

    /**
     * Whether the deletion would proceed: nothing vetoed it and no referencing resource blocks it. Note that the
     * requesting user's permissions are checked when the deletion is actually attempted, not here.
     *
     * @return {@code true} if the deletion would proceed
     */
    public boolean isExecutable()
    {
        return this.vetoes.isEmpty() && this.referrers.isEmpty() && this.inaccessibleReferrerCount == 0;
    }

    /**
     * A human-readable description of what blocks the deletion, e.g.
     * {@code "This item is referenced in 3 submissions (S-1, S-2, S-3) and 1 schema (Onboarding)."}.
     *
     * @return a sentence, empty when nothing blocks the deletion
     */
    @NotNull
    public String getSummary()
    {
        return this.summary;
    }
}
