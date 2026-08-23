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
package io.uhndata.iap.tags.models;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.tags.api.Tag;
import io.uhndata.iap.tags.internal.TagOperations;

/**
 * The tags-aware view of a piece of content, and the entry point for working with its tags: view any content model
 * as this one, {@code content.as(Taggable.class)}, then read, place, or remove tags. Reads distinguish between the
 * tags <em>explicitly</em> placed here ({@link #getTags}) and the tags this content <em>effectively</em> carries
 * ({@link #getEffectiveTags}): the explicit ones, plus {@link TagDefinition#isInheritable() inheritable} tags
 * placed on an ancestor, plus {@link TagDefinition#isAggregated() aggregated} tags placed on a descendant. Writes
 * validate against the tag definitions, only modify the in-memory content, and are the caller's to commit through
 * the resolver this model was read with.
 *
 * <p>
 * The view adapts any content; the {@code tag:Taggable} mixin only declares the tagging properties once — every
 * {@code data:Content} node carries it through its supertypes, and other node types, e.g. {@code nt:file}, become
 * taggable by adding the mixin.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Taggable extends Content
{
    // The internal service the behavior delegates to; the model only hands it its own wrapped resource, which
    // keeps the resource itself out of the public API
    @OSGiService
    private TagOperations operations;

    /**
     * The defined tags that may be placed on this content, in display order.
     *
     * @return the applicable tag definitions, an empty list if none apply
     */
    @NotNull
    public List<TagDefinition> getApplicableDefinitions()
    {
        return this.operations == null ? List.of() : this.operations.getApplicableDefinitions(this.resource);
    }

    /**
     * The tags explicitly placed on this content, in storage order.
     *
     * @return a snapshot of the content's own tag names, an empty set if it has none
     */
    @NotNull
    public Set<String> getTags()
    {
        return this.operations == null ? Set.of() : this.operations.getTags(this.resource);
    }

    /**
     * All the tags this content effectively carries: the tags belonging to it, the
     * {@link TagDefinition#isInheritable() inheritable} tags of its ancestors, and the
     * {@link TagDefinition#isAggregated() aggregated} tags of its descendants. The content's own tags are both the
     * ones explicitly placed on it and the ones a {@code TagProcessor} computed for it, distinguished by their
     * {@link Tag#getOrigins() origin}. Note that computing the aggregated tags visits the whole subtree, which may
     * be slow on very large subtrees.
     *
     * @return the effective tags, with their definitions and origins, an empty collection if there are none
     */
    @NotNull
    public Collection<Tag> getEffectiveTags()
    {
        return this.operations == null ? List.of() : this.operations.getEffectiveTags(this.resource);
    }

    /**
     * The names of all the tags this content effectively carries, read from the explicit {@code tags} property and
     * from the derived tag properties materialized at commit time, one per {@code TagProcessor.Phase}. This is the
     * cheap variant of {@link #getEffectiveTags}: it reads a few properties of the content itself instead of
     * walking the tree, at the cost of losing the origin information, and of reporting exactly what was
     * materialized, no more and no less. In particular it does not see
     * <ul>
     * <li>changes not yet propagated, e.g. uncommitted ones;</li>
     * <li>tags on the far side of a <em>propagation boundary</em> — strict node types that reject the derived
     * properties, e.g. file contents or access control policies, stop the copies from travelling through them, so
     * such tags are reported by the tree-walking methods but neither here nor by a query;</li>
     * <li>the deletion of a definition: a derived copy left behind by a tag that stopped being defined, or stopped
     * being inheritable or aggregated, is reported until the node is recomputed, while {@link #getEffectiveTags}
     * and {@link #hasTag} filter it out immediately.</li>
     * </ul>
     *
     * @return the effective tag names, an empty set if there are none
     */
    @NotNull
    public Set<String> getEffectiveTagNames()
    {
        return this.operations == null ? Set.of() : this.operations.getEffectiveTagNames(this.resource);
    }

    /**
     * Checks whether this content effectively carries a tag, cheaper than computing all the
     * {@link #getEffectiveTags effective tags}.
     *
     * @param name a tag name
     * @return {@code true} if the tag belongs to this content, whether explicitly placed on it or computed for it,
     *         or is inherited from an ancestor, or aggregated from a descendant
     */
    public boolean hasTag(@NotNull final String name)
    {
        return this.operations != null && this.operations.hasTag(this.resource, name);
    }

    /**
     * Checks whether the given tag is explicitly placed on this content.
     *
     * @param name a tag name
     * @return {@code true} if the tag is present in the content's own {@code tags} property
     */
    public boolean hasOwnTag(@NotNull final String name)
    {
        return this.operations != null && this.operations.hasOwnTag(this.resource, name);
    }

    /**
     * Places a tag on this content, in memory: committing it is the caller's responsibility, through the resolver
     * this model was read with.
     *
     * @param name the name of a defined, non-system tag applicable to this content
     * @return {@code true} if the content was modified, {@code false} if it already carried the tag
     * @throws IllegalArgumentException if the tag is not defined, may not be placed on this content, or is a
     *             system tag
     * @throws IllegalStateException if the tags service is not available
     * @throws PersistenceException if the content cannot be modified by the current session
     */
    public boolean tag(@NotNull final String name) throws PersistenceException
    {
        return tag(name, false);
    }

    /**
     * Places a tag on this content, optionally allowing platform-managed system tags. The change is made in
     * memory: committing it is the caller's responsibility, through the resolver this model was read with.
     *
     * @param name the name of a defined tag applicable to this content
     * @param allowSystem when {@code true}, {@link TagDefinition#isSystem() system} tags may be placed; reserved
     *            for the platform code managing the tag in question
     * @return {@code true} if the content was modified, {@code false} if it already carried the tag
     * @throws IllegalArgumentException if the tag is not defined, may not be placed on this content, or is a
     *             system tag and {@code allowSystem} is not set
     * @throws IllegalStateException if the tags service is not available
     * @throws PersistenceException if the content cannot be modified by the current session
     */
    public boolean tag(@NotNull final String name, final boolean allowSystem) throws PersistenceException
    {
        return requireOperations().tag(this.resource, name, allowSystem);
    }

    /**
     * Removes a tag from this content. Undefined tags, e.g. left behind after their definition was deleted, may be
     * removed too. The change is made in memory: committing it is the caller's responsibility, through the
     * resolver this model was read with.
     *
     * @param name the name of a non-system tag
     * @return {@code true} if the content was modified, {@code false} if it didn't carry the tag
     * @throws IllegalArgumentException if the tag is defined as a system tag
     * @throws IllegalStateException if the tags service is not available
     * @throws PersistenceException if the content cannot be modified by the current session
     */
    public boolean untag(@NotNull final String name) throws PersistenceException
    {
        return untag(name, false);
    }

    /**
     * Removes a tag from this content, optionally allowing platform-managed system tags. The change is made in
     * memory: committing it is the caller's responsibility, through the resolver this model was read with.
     *
     * @param name a tag name
     * @param allowSystem when {@code true}, {@link TagDefinition#isSystem() system} tags may be removed; reserved
     *            for the platform code managing the tag in question
     * @return {@code true} if the content was modified, {@code false} if it didn't carry the tag
     * @throws IllegalArgumentException if the tag is defined as a system tag and {@code allowSystem} is not set
     * @throws IllegalStateException if the tags service is not available
     * @throws PersistenceException if the content cannot be modified by the current session
     */
    public boolean untag(@NotNull final String name, final boolean allowSystem) throws PersistenceException
    {
        return requireOperations().untag(this.resource, name, allowSystem);
    }

    /**
     * Replaces the tags explicitly placed on this content, in memory: committing it is the caller's
     * responsibility, through the resolver this model was read with.
     *
     * @param names the names of defined, non-system tags applicable to this content; system tags already placed
     *            must be included, since they cannot be removed by this method
     * @throws IllegalArgumentException if a tag to be added is not defined or may not be placed on this content,
     *             or if the change would add or remove a system tag
     * @throws IllegalStateException if the tags service is not available
     * @throws PersistenceException if the content cannot be modified by the current session
     */
    public void setTags(@NotNull final Collection<String> names) throws PersistenceException
    {
        setTags(names, false);
    }

    /**
     * Replaces the tags explicitly placed on this content, optionally allowing platform-managed system tags. The
     * change is made in memory: committing it is the caller's responsibility, through the resolver this model was
     * read with.
     *
     * @param names the names of defined tags applicable to this content
     * @param allowSystem when {@code true}, {@link TagDefinition#isSystem() system} tags may be added and removed;
     *            reserved for the platform code managing the tags in question
     * @throws IllegalArgumentException if a tag to be added is not defined or may not be placed on this content,
     *             or if the change would add or remove a system tag and {@code allowSystem} is not set
     * @throws IllegalStateException if the tags service is not available
     * @throws PersistenceException if the content cannot be modified by the current session
     */
    public void setTags(@NotNull final Collection<String> names, final boolean allowSystem)
        throws PersistenceException
    {
        requireOperations().setTags(this.resource, names, allowSystem);
    }

    private TagOperations requireOperations()
    {
        if (this.operations == null) {
            throw new IllegalStateException("The tags service is not available");
        }
        return this.operations;
    }
}
