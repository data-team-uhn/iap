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
 * Reads, places and removes the tags of a piece of content. View any content model as this one,
 * {@code content.as(Taggable.class)}. {@link #getTags} gives the tags placed on the content. {@link #getEffectiveTags}
 * gives every tag it carries: its own, the {@link TagDefinition#isInheritable() inheritable} tags of its ancestors,
 * and the {@link TagDefinition#isAggregated() aggregated} tags of its descendants. Writes are checked against the
 * tag definitions and change the content in memory. The caller commits them through the resolver this model was read
 * with.
 *
 * <p>
 * Any content adapts to this model. Storing the tags needs a node type that declares them, as the
 * {@code tag:Taggable} mixin does. Every {@code data:Content} node has it, and a node of another type, such as
 * {@code nt:file}, gets it by adding the mixin.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Taggable extends Content
{
    // Not exported, and handed only this model's own resource
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
     * All the tags this content carries: the tags belonging to it, the
     * {@link TagDefinition#isInheritable() inheritable} tags of its ancestors, and the
     * {@link TagDefinition#isAggregated() aggregated} tags of its descendants. The content's own tags are those placed
     * on it and those a {@code TagProcessor} computed for it, told apart by their {@link Tag#getOrigins() origin}.
     * Finding the aggregated tags visits the subtree down to the nearest boundaries, which can be slow.
     *
     * @return the effective tags, with their definitions and origins, an empty collection if there are none
     */
    @NotNull
    public Collection<Tag> getEffectiveTags()
    {
        return this.operations == null ? List.of() : this.operations.getEffectiveTags(this.resource);
    }

    /**
     * The names of all the tags this content carries. It reads the {@code tags} property and the derived tag
     * properties written at commit time, one per {@code TagProcessor.Phase}, instead of walking the tree, and gives
     * no origins. It reports what is stored, so it differs from
     * {@link #getEffectiveTags} in three cases:
     * <ul>
     * <li>it does not see changes not yet committed;</li>
     * <li>it does not see a tag beyond a node whose types do not declare the tag properties, since the copies stop
     * there, and neither does a query;</li>
     * <li>it still reports a copy of a tag whose definition was deleted, or stopped being inheritable or aggregated,
     * until the node is recomputed.</li>
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
     * Checks whether this content carries a tag, by walking the tree for that one tag.
     *
     * @param name a tag name
     * @return {@code true} if the tag belongs to this content, placed on it or computed for it, or is inherited from
     *         an ancestor, or is aggregated from a descendant outside any {@code tag:Boundary}
     */
    public boolean hasTag(@NotNull final String name)
    {
        return this.operations != null && this.operations.hasTag(this.resource, name);
    }

    /**
     * Checks whether the given tag is placed on this content.
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
     * Places a tag on this content in place of its own tags that share a category with it. That is how content
     * moves from one state to the next. Tags of other categories, and undefined ones, are kept. The change is made
     * in memory: committing it is the caller's responsibility, through the resolver this model was read with.
     *
     * @param name the name of a defined tag applicable to this content
     * @param allowSystem when {@code true}, {@link TagDefinition#isSystem() system} tags may be placed and retired;
     *            reserved for the platform code managing the tags in question
     * @return {@code true} if the content was modified, {@code false} if it already carried the tag and nothing it
     *         replaces
     * @throws IllegalArgumentException if the tag is not defined or may not be placed on this content, or if it or a
     *             tag it retires is a system tag and {@code allowSystem} is not set
     * @throws IllegalStateException if the tags service is not available
     * @throws PersistenceException if the content cannot be modified by the current session
     */
    public boolean tagReplacing(@NotNull final String name, final boolean allowSystem) throws PersistenceException
    {
        return requireOperations().tagReplacing(this.resource, name, allowSystem);
    }

    /**
     * Removes a tag from this content. An undefined tag, such as one whose definition was deleted, may be removed
     * too. The change is made in memory: committing it is the caller's responsibility, through the
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
