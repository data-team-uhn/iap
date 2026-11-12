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
 * What a deletion removes, and what blocks it.
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
     * @param itemPaths the absolute paths of the subtrees to delete, none inside another
     * @param removedLinkPaths the absolute paths of the link nodes to remove from resources that stay
     * @param vetoes the guards' objections; empty if none objected
     * @param referrers the resources that block the deletion by referencing it, grouped by node type
     * @param inaccessibleReferrerCount how many blocking resources are counted but left out of {@code referrers}
     * @param summary a sentence for the requesting user saying what blocks the deletion; empty if nothing does
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
     * The subtrees to delete: the requested resource, and the resources its deletion takes along. A recursive
     * deletion takes along the resources that reference a deleted one. It also takes along the resources holding a
     * link to a deleted one, when the link's definition says {@code RECURSIVE_DELETE}.
     *
     * @return an immutable list of absolute paths, none inside another
     */
    @NotNull
    public List<String> getItemPaths()
    {
        return this.itemPaths;
    }

    /**
     * The link nodes to remove from resources that stay, because the link points at a deleted resource.
     *
     * @return an immutable list of absolute paths; empty if no link is removed
     */
    @NotNull
    public List<String> getRemovedLinkPaths()
    {
        return this.removedLinkPaths;
    }

    /**
     * The guards' objections. A single one blocks the whole deletion.
     *
     * @return an immutable list; empty if no guard objected
     */
    @NotNull
    public List<Veto> getVetoes()
    {
        return this.vetoes;
    }

    /**
     * The resources that block a deletion that is not recursive: those referencing a resource to delete, and those
     * holding a {@code RECURSIVE_DELETE} link to one. A recursive deletion deletes them instead. The resources the
     * requesting user cannot read are only counted, in {@link #getInaccessibleReferrerCount()}.
     *
     * @return an immutable list of groups, one per primary node type; empty for a recursive deletion, or if nothing
     *         blocks it
     */
    @NotNull
    public List<ReferrerGroup> getReferrers()
    {
        return this.referrers;
    }

    /**
     * How many blocking resources are counted but not listed in {@link #getReferrers()}. These are the referencing
     * resources the requesting user cannot read, and those that cannot be described. For a permanent deletion they
     * also include the archived resources that reference a resource to delete. A deletion never deletes archived
     * content along with it.
     *
     * @return the count; {@code 0} if every blocking resource is listed
     */
    public long getInaccessibleReferrerCount()
    {
        return this.inaccessibleReferrerCount;
    }

    /**
     * Whether nothing blocks the deletion: no guard objected, and no referencing resource blocks it. The requesting
     * user's permissions are not part of this.
     *
     * @return {@code true} if nothing blocks the deletion
     */
    public boolean isExecutable()
    {
        return this.vetoes.isEmpty() && this.referrers.isEmpty() && this.inaccessibleReferrerCount == 0;
    }

    /**
     * What blocks the deletion, in a sentence for the requesting user, such as
     * {@code "This item is referenced by 3 submissions (S-1, S-2, S-3) and 1 schema (Onboarding)."}. Vetoes are not
     * part of it.
     *
     * @return the sentence; empty if no resource references the deleted ones
     */
    @NotNull
    public String getSummary()
    {
        return this.summary;
    }
}
