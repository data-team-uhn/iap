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

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.tags.models.TagDefinition;

/**
 * Works with the tags placed on resources through their multivalued {@code tags} property, and with the
 * {@code iap:TagDefinition} nodes under {@code /Tags} defining them.
 *
 * <p>
 * Reading distinguishes between the tags <em>explicitly</em> placed on a resource ({@link #getTags}) and the tags a
 * resource <em>effectively</em> carries ({@link #getEffectiveTags}): the explicit ones, plus
 * {@link TagDefinition#isInheritable() inheritable} tags placed on an ancestor, plus
 * {@link TagDefinition#isAggregated() aggregated} tags placed on a descendant.
 * </p>
 *
 * <p>
 * Writing validates against the tag definitions: only defined tags, allowed on the target resource, may be added.
 * Tags defined as {@link TagDefinition#isSystem() system} tags are rejected unless the platform-reserved method
 * variants are explicitly used. Write methods only modify the in-memory resource; like other Sling persistence
 * operations, the changes must be committed by the caller through
 * {@link org.apache.sling.api.resource.ResourceResolver#commit}.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TagManager
{
    /** The name of the property holding the tags of a resource, declared for all {@code iap:Content} nodes. */
    String TAGS_PROPERTY = "tags";

    /** The path of the {@code iap:TagsHomepage} node holding the tag definitions. */
    String DEFINITIONS_PATH = "/Tags";

    /**
     * All the defined tags, in display order.
     *
     * @param resolver the resource resolver to access the definitions with
     * @return the tag definitions, an empty list if there are none
     */
    List<TagDefinition> getDefinitions(ResourceResolver resolver);

    /**
     * The definition of the given tag.
     *
     * @param resolver the resource resolver to access the definitions with
     * @param name a tag name
     * @return the definition whose {@link TagDefinition#getName() name} is exactly {@code name}, or {@code null} if
     *         the tag is not defined
     */
    TagDefinition getDefinition(ResourceResolver resolver, String name);

    /**
     * The defined tags matching the given filters, in display order.
     *
     * @param resolver the resource resolver to access the definitions with
     * @param category if not blank, only definitions listing this category (ignoring case) are returned
     * @param query if not blank, only definitions containing this text (ignoring case) in their name, label, or
     *            description are returned
     * @return the matching tag definitions, an empty list if none match
     */
    List<TagDefinition> findDefinitions(ResourceResolver resolver, String category, String query);

    /**
     * The defined tags that may be placed on the given resource, in display order.
     *
     * @param resource a candidate resource to be tagged
     * @return the tag definitions {@link TagDefinition#appliesTo applying to} the resource, an empty list if none do
     */
    List<TagDefinition> getApplicableDefinitions(Resource resource);

    /**
     * The tags explicitly placed on the given resource, in storage order.
     *
     * @param resource the resource to read
     * @return a snapshot of the resource's own tag names, an empty set if it has none
     */
    Set<String> getTags(Resource resource);

    /**
     * All the tags the given resource effectively carries: the tags explicitly placed on it, the
     * {@link TagDefinition#isInheritable() inheritable} tags placed on its ancestors, and the
     * {@link TagDefinition#isAggregated() aggregated} tags placed on its descendants. Note that computing the
     * aggregated tags visits the whole subtree, which may be slow on very large subtrees.
     *
     * @param resource the resource to read
     * @return the effective tags, with their definitions and origins, an empty collection if there are none
     */
    Collection<Tag> getEffectiveTags(Resource resource);

    /**
     * The names of all the tags the given resource effectively carries, read from the explicit {@code tags}
     * property and the derived tag properties materialized at commit time by the registered
     * {@code TagProcessor}s. This is the cheap variant of {@link #getEffectiveTags}: it reads a few properties of
     * the resource itself instead of walking the tree, at the cost of losing the origin information, and of not
     * seeing changes not yet propagated, e.g. uncommitted ones.
     *
     * @param resource the resource to read
     * @return the effective tag names, an empty set if there are none
     */
    Set<String> getEffectiveTagNames(Resource resource);

    /**
     * Checks whether the given resource effectively carries a tag, cheaper than computing all the
     * {@link #getEffectiveTags effective tags}.
     *
     * @param resource the resource to read
     * @param name a tag name
     * @return {@code true} if the tag is explicitly placed on the resource, inherited from an ancestor, or
     *         aggregated from a descendant
     */
    boolean hasTag(Resource resource, String name);

    /**
     * Checks whether the given tag is explicitly placed on the given resource.
     *
     * @param resource the resource to read
     * @param name a tag name
     * @return {@code true} if the tag is present in the resource's own {@code tags} property
     */
    boolean hasOwnTag(Resource resource, String name);

    /**
     * Places a tag on a resource. The change is not committed, the caller must commit the resource resolver.
     *
     * @param resource the resource to tag
     * @param name the name of a defined, non-system tag applicable to the resource
     * @return {@code true} if the resource was modified, {@code false} if it already carried the tag
     * @throws IllegalArgumentException if the tag is not defined, may not be placed on this resource, or is a
     *             system tag
     * @throws PersistenceException if the resource cannot be modified by the current session
     */
    boolean tag(Resource resource, String name) throws PersistenceException;

    /**
     * Places a tag on a resource, optionally allowing platform-managed system tags. The change is not committed, the
     * caller must commit the resource resolver.
     *
     * @param resource the resource to tag
     * @param name the name of a defined tag applicable to the resource
     * @param allowSystem when {@code true}, {@link TagDefinition#isSystem() system} tags may be placed; reserved for
     *            the platform code managing the tag in question
     * @return {@code true} if the resource was modified, {@code false} if it already carried the tag
     * @throws IllegalArgumentException if the tag is not defined, may not be placed on this resource, or is a
     *             system tag and {@code allowSystem} is not set
     * @throws PersistenceException if the resource cannot be modified by the current session
     */
    boolean tag(Resource resource, String name, boolean allowSystem) throws PersistenceException;

    /**
     * Removes a tag from a resource. Undefined tags, e.g. left behind after their definition was deleted, may be
     * removed too. The change is not committed, the caller must commit the resource resolver.
     *
     * @param resource the resource to untag
     * @param name the name of a non-system tag
     * @return {@code true} if the resource was modified, {@code false} if it didn't carry the tag
     * @throws IllegalArgumentException if the tag is defined as a system tag
     * @throws PersistenceException if the resource cannot be modified by the current session
     */
    boolean untag(Resource resource, String name) throws PersistenceException;

    /**
     * Removes a tag from a resource, optionally allowing platform-managed system tags. The change is not committed,
     * the caller must commit the resource resolver.
     *
     * @param resource the resource to untag
     * @param name a tag name
     * @param allowSystem when {@code true}, {@link TagDefinition#isSystem() system} tags may be removed; reserved
     *            for the platform code managing the tag in question
     * @return {@code true} if the resource was modified, {@code false} if it didn't carry the tag
     * @throws IllegalArgumentException if the tag is defined as a system tag and {@code allowSystem} is not set
     * @throws PersistenceException if the resource cannot be modified by the current session
     */
    boolean untag(Resource resource, String name, boolean allowSystem) throws PersistenceException;

    /**
     * Replaces the tags explicitly placed on a resource. The change is not committed, the caller must commit the
     * resource resolver.
     *
     * @param resource the resource to tag
     * @param names the names of defined, non-system tags applicable to the resource; system tags already on the
     *            resource must be included, since they cannot be removed by this method
     * @throws IllegalArgumentException if a tag to be added is not defined or may not be placed on this resource,
     *             or if the change would add or remove a system tag
     * @throws PersistenceException if the resource cannot be modified by the current session
     */
    void setTags(Resource resource, Collection<String> names) throws PersistenceException;

    /**
     * Replaces the tags explicitly placed on a resource, optionally allowing platform-managed system tags. The
     * change is not committed, the caller must commit the resource resolver.
     *
     * @param resource the resource to tag
     * @param names the names of defined tags applicable to the resource
     * @param allowSystem when {@code true}, {@link TagDefinition#isSystem() system} tags may be added and removed;
     *            reserved for the platform code managing the tags in question
     * @throws IllegalArgumentException if a tag to be added is not defined or may not be placed on this resource,
     *             or if the change would add or remove a system tag and {@code allowSystem} is not set
     * @throws PersistenceException if the resource cannot be modified by the current session
     */
    void setTags(Resource resource, Collection<String> names, boolean allowSystem) throws PersistenceException;
}
