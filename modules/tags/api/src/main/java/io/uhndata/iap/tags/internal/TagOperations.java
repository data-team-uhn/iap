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
package io.uhndata.iap.tags.internal;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.tags.api.Tag;
import io.uhndata.iap.tags.models.TagDefinition;
import io.uhndata.iap.tags.models.Taggable;

/**
 * The internal service backing the behavior offered by the {@link Taggable} model. It works on the model's raw
 * node, which the model passes in itself — always its own wrapped resource — so this interface must stay in this
 * non-exported package: the models are the only public face of the tags placed on content, and the resources they
 * wrap are not to be reachable through the public API.
 *
 * <p>
 * Reads and writes go through the resolver the given resource carries, so they are subject to the caller's own
 * permissions; writes only modify the in-memory resource and are the caller's to commit.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TagOperations
{
    /**
     * The defined tags that may be placed on the given node, in display order.
     *
     * @param resource a candidate node to be tagged
     * @return the applicable tag definitions, an empty list if none apply
     */
    @NotNull
    List<TagDefinition> getApplicableDefinitions(@NotNull Resource resource);

    /**
     * The tags explicitly placed on the given node, in storage order.
     *
     * @param resource the node to read
     * @return a snapshot of the node's own tag names, an empty set if it has none
     */
    @NotNull
    Set<String> getTags(@NotNull Resource resource);

    /**
     * All the tags the given node effectively carries, with their definitions and origins, computed by walking the
     * tree.
     *
     * @param resource the node to read
     * @return the effective tags, an empty collection if there are none
     */
    @NotNull
    Collection<Tag> getEffectiveTags(@NotNull Resource resource);

    /**
     * The names of all the tags the given node effectively carries, read from the explicit and materialized tag
     * properties without walking the tree.
     *
     * @param resource the node to read
     * @return the effective tag names, an empty set if there are none
     */
    @NotNull
    Set<String> getEffectiveTagNames(@NotNull Resource resource);

    /**
     * Checks whether the given node effectively carries a tag.
     *
     * @param resource the node to read
     * @param name a tag name
     * @return {@code true} if the tag belongs to the node, is inherited from an ancestor, or is aggregated from a
     *         descendant
     */
    boolean hasTag(@NotNull Resource resource, @NotNull String name);

    /**
     * Checks whether the given tag is explicitly placed on the given node.
     *
     * @param resource the node to read
     * @param name a tag name
     * @return {@code true} if the tag is present in the node's own {@code tags} property
     */
    boolean hasOwnTag(@NotNull Resource resource, @NotNull String name);

    /**
     * Places a tag on a node, in memory: committing it is the caller's responsibility.
     *
     * @param resource the node to tag
     * @param name the name of a defined tag applicable to the node
     * @param allowSystem when {@code true}, system tags may be placed
     * @return {@code true} if the node was modified, {@code false} if it already carried the tag
     * @throws IllegalArgumentException if the tag is not defined, may not be placed on this node, or is a system
     *             tag and {@code allowSystem} is not set
     * @throws PersistenceException if the node cannot be modified by the current session
     */
    boolean tag(@NotNull Resource resource, @NotNull String name, boolean allowSystem) throws PersistenceException;

    /**
     * Removes a tag from a node, in memory: committing it is the caller's responsibility.
     *
     * @param resource the node to untag
     * @param name a tag name
     * @param allowSystem when {@code true}, system tags may be removed
     * @return {@code true} if the node was modified, {@code false} if it didn't carry the tag
     * @throws IllegalArgumentException if the tag is defined as a system tag and {@code allowSystem} is not set
     * @throws PersistenceException if the node cannot be modified by the current session
     */
    boolean untag(@NotNull Resource resource, @NotNull String name, boolean allowSystem)
        throws PersistenceException;

    /**
     * Replaces the tags explicitly placed on a node, in memory: committing it is the caller's responsibility.
     *
     * @param resource the node to tag
     * @param names the names of defined tags applicable to the node
     * @param allowSystem when {@code true}, system tags may be added and removed
     * @throws IllegalArgumentException if a tag to be added is not defined or may not be placed on this node, or
     *             if the change would add or remove a system tag and {@code allowSystem} is not set
     * @throws PersistenceException if the node cannot be modified by the current session
     */
    void setTags(@NotNull Resource resource, @NotNull Collection<String> names, boolean allowSystem)
        throws PersistenceException;
}
