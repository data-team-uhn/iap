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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.tags.api.Tag;
import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.models.TagDefinition;
import io.uhndata.iap.tags.spi.TagProcessor;

/**
 * Default implementation of {@link TagManager}.
 *
 * <p>
 * The definitions are read with the manager's own service user, since they are world-readable platform vocabulary
 * needed by callers that may have no user session at all, and they are cached until {@code /Tags} changes: they are
 * looked up on nearly every tag operation, while being edited very rarely. The cached definitions are Sling Models
 * bound to the resolver they were read with, which is kept open for as long as they are cached and closed once a
 * newer set replaces them, so reading a definition's own values is always safe.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { TagManager.class, ResourceChangeListener.class },
    property = { ResourceChangeListener.PATHS + "=" + TagManager.DEFINITIONS_PATH })
public class TagManagerImpl implements TagManager, ResourceChangeListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(TagManagerImpl.class);

    /** The subservice name mapped to the service user allowed to read the tag definitions. */
    private static final Map<String, Object> SERVICE_USER = Map.of(ResourceResolverFactory.SUBSERVICE, "tags");

    @Reference
    private ResourceResolverFactory resolverFactory;

    /** Guards the cached definitions and the resolver they are bound to. */
    private final Object definitionsLock = new Object();

    /** The definitions as last read from {@code /Tags}, {@code null} when the cache must be rebuilt. */
    private List<TagDefinition> definitions;

    /** The resolver the cached definitions are bound to, closed when a newer set replaces them. */
    private ResourceResolver definitionsResolver;

    /**
     * Gathers the origins and sources of one effective tag while the resource tree is visited.
     *
     * @since 0.1.0
     */
    private static final class TagOccurrences
    {
        private final Set<Tag.Origin> origins = EnumSet.noneOf(Tag.Origin.class);

        private final Set<String> sources = new LinkedHashSet<>();
    }

    @Deactivate
    protected void deactivate()
    {
        synchronized (this.definitionsLock) {
            this.definitions = null;
            closeResolver();
        }
    }

    @Override
    public void onChange(final List<ResourceChange> changes)
    {
        synchronized (this.definitionsLock) {
            // Drop the cache; the resolver the stale definitions are bound to is closed by the next read
            this.definitions = null;
        }
    }

    @Override
    public List<TagDefinition> getDefinitions()
    {
        synchronized (this.definitionsLock) {
            if (this.definitions == null) {
                readDefinitions();
            }
            return this.definitions;
        }
    }

    @Override
    public TagDefinition getDefinition(final String name)
    {
        return getDefinitions().stream()
            .filter(definition -> definition.getName().equals(name))
            .findFirst().orElse(null);
    }

    @Override
    public List<TagDefinition> findDefinitions(final String category, final String query)
    {
        return getDefinitions().stream()
            .filter(definition -> matchesCategory(definition, category))
            .filter(definition -> matchesQuery(definition, query))
            .toList();
    }

    @Override
    public List<TagDefinition> getApplicableDefinitions(final Resource resource)
    {
        return getDefinitions().stream()
            .filter(definition -> definition.appliesTo(resource))
            .toList();
    }

    @Override
    public Set<String> getTags(final Resource resource)
    {
        return readTags(resource, TAGS_PROPERTY);
    }

    @Override
    public Set<String> getEffectiveTagNames(final Resource resource)
    {
        final Set<String> result = getTags(resource);
        for (final TagProcessor.Phase phase : TagProcessor.Phase.values()) {
            result.addAll(readTags(resource, phase.getPropertyName()));
        }
        return result;
    }

    @Override
    public Collection<Tag> getEffectiveTags(final Resource resource)
    {
        final Map<String, TagDefinition> knownDefinitions = new HashMap<>();
        getDefinitions().forEach(definition -> knownDefinitions.put(definition.getName(), definition));
        final Map<String, TagOccurrences> found = new LinkedHashMap<>();

        // The resource's own tags, whether defined or not, placed explicitly or computed from its content
        getTags(resource).forEach(name -> record(found, name, Tag.Origin.EXPLICIT, resource.getPath()));
        readTags(resource, TagProcessor.Phase.LOCAL.getPropertyName())
            .forEach(name -> record(found, name, Tag.Origin.COMPUTED, resource.getPath()));
        collectInherited(resource, knownDefinitions, found);
        collectAggregated(resource, knownDefinitions, found);

        final List<Tag> result = new ArrayList<>();
        found.forEach((name, occurrences) ->
            result.add(new Tag(name, knownDefinitions.get(name), occurrences.origins, occurrences.sources)));
        return result;
    }

    @Override
    public boolean hasTag(final Resource resource, final String name)
    {
        if (ownTags(resource).contains(name)) {
            return true;
        }
        final TagDefinition definition = getDefinition(name);
        if (definition == null) {
            return false;
        }
        if (definition.isInheritable()) {
            for (Resource ancestor = resource.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                if (ownTags(ancestor).contains(name)) {
                    return true;
                }
            }
        }
        if (definition.isAggregated()) {
            final Deque<Resource> toVisit = new ArrayDeque<>();
            resource.getChildren().forEach(toVisit::add);
            while (!toVisit.isEmpty()) {
                final Resource descendant = toVisit.removeFirst();
                if (ownTags(descendant).contains(name)) {
                    return true;
                }
                descendant.getChildren().forEach(toVisit::add);
            }
        }
        return false;
    }

    @Override
    public boolean hasOwnTag(final Resource resource, final String name)
    {
        return getTags(resource).contains(name);
    }

    @Override
    public boolean tag(final Resource resource, final String name) throws PersistenceException
    {
        return tag(resource, name, false);
    }

    @Override
    public boolean tag(final Resource resource, final String name, final boolean allowSystem)
        throws PersistenceException
    {
        checkAddable(resource, name, allowSystem);
        final Set<String> tags = getTags(resource);
        if (!tags.add(name)) {
            return false;
        }
        write(resource, tags);
        return true;
    }

    @Override
    public boolean untag(final Resource resource, final String name) throws PersistenceException
    {
        return untag(resource, name, false);
    }

    @Override
    public boolean untag(final Resource resource, final String name, final boolean allowSystem)
        throws PersistenceException
    {
        checkRemovable(name, allowSystem);
        final Set<String> tags = getTags(resource);
        if (!tags.remove(name)) {
            return false;
        }
        write(resource, tags);
        return true;
    }

    @Override
    public void setTags(final Resource resource, final Collection<String> names) throws PersistenceException
    {
        setTags(resource, names, false);
    }

    @Override
    public void setTags(final Resource resource, final Collection<String> names, final boolean allowSystem)
        throws PersistenceException
    {
        final Set<String> current = getTags(resource);
        final Set<String> target = new LinkedHashSet<>(names);
        if (current.equals(target)) {
            return;
        }
        for (final String name : target) {
            if (!current.contains(name)) {
                checkAddable(resource, name, allowSystem);
            }
        }
        for (final String name : current) {
            if (!target.contains(name)) {
                checkRemovable(name, allowSystem);
            }
        }
        write(resource, target);
    }

    /**
     * Reads the definitions from {@code /Tags} into the cache, with the service user's own resolver. The resolver
     * the previous set was bound to is closed once the new one is in place. Must be called while holding the lock.
     */
    private void readDefinitions()
    {
        final ResourceResolver previous = this.definitionsResolver;
        try {
            this.definitionsResolver = this.resolverFactory.getServiceResourceResolver(SERVICE_USER);
        } catch (final LoginException e) {
            this.definitionsResolver = previous;
            this.definitions = List.of();
            LOGGER.error("Cannot read the tag definitions, the tags service user is not available: {}",
                e.getMessage(), e);
            return;
        }
        final Resource homepage = this.definitionsResolver.getResource(DEFINITIONS_PATH);
        this.definitions = homepage == null ? List.of()
            : StreamSupport.stream(homepage.getChildren().spliterator(), false)
                .filter(child -> child.isResourceType(TagDefinition.RESOURCE_TYPE))
                .map(child -> child.adaptTo(TagDefinition.class))
                .filter(Objects::nonNull)
                .sorted(TagDefinition.DISPLAY_ORDER)
                .toList();
        if (previous != null) {
            previous.close();
        }
    }

    private void closeResolver()
    {
        if (this.definitionsResolver != null) {
            this.definitionsResolver.close();
            this.definitionsResolver = null;
        }
    }

    private boolean matchesCategory(final TagDefinition definition, final String category)
    {
        return category == null || category.isBlank()
            || definition.getCategories().stream().anyMatch(category::equalsIgnoreCase);
    }

    private boolean matchesQuery(final TagDefinition definition, final String query)
    {
        if (query == null || query.isBlank()) {
            return true;
        }
        final String needle = query.toLowerCase(Locale.ROOT);
        return contains(definition.getName(), needle) || contains(definition.getLabel(), needle)
            || contains(definition.getDescription(), needle);
    }

    private boolean contains(final String haystack, final String lowercaseNeedle)
    {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
    }

    /**
     * Checks that a tag may be placed on a resource: it must be defined, applicable to the resource, and not a
     * system tag unless system tags are explicitly allowed.
     *
     * @param resource the resource to tag
     * @param name the tag name
     * @param allowSystem whether system tags are allowed
     * @throws IllegalArgumentException if the tag may not be placed
     */
    private void checkAddable(final Resource resource, final String name, final boolean allowSystem)
    {
        final TagDefinition definition = getDefinition(name);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown tag: " + name);
        }
        if (!definition.appliesTo(resource)) {
            throw new IllegalArgumentException(
                "Tag " + name + " may not be placed on a " + resource.getResourceType() + " resource");
        }
        checkSystem(definition, allowSystem);
    }

    /**
     * Checks that a tag may be removed from a resource: it must not be a system tag unless system tags are
     * explicitly allowed. Undefined tags may always be removed.
     *
     * @param name the tag name
     * @param allowSystem whether system tags are allowed
     * @throws IllegalArgumentException if the tag may not be removed
     */
    private void checkRemovable(final String name, final boolean allowSystem)
    {
        checkSystem(getDefinition(name), allowSystem);
    }

    private void checkSystem(final TagDefinition definition, final boolean allowSystem)
    {
        if (definition != null && definition.isSystem() && !allowSystem) {
            throw new IllegalArgumentException(
                "Tag " + definition.getName() + " is managed by the platform and cannot be manually changed");
        }
    }

    /**
     * Notes that one resource carries a tag, reached in one particular way.
     *
     * @param found the occurrences gathered so far, updated in place
     * @param name the tag name
     * @param origin how the tag reached the resource being described
     * @param source the path of the resource the tag belongs to
     */
    private void record(final Map<String, TagOccurrences> found, final String name, final Tag.Origin origin,
        final String source)
    {
        final TagOccurrences occurrences = found.computeIfAbsent(name, key -> new TagOccurrences());
        occurrences.origins.add(origin);
        occurrences.sources.add(source);
    }

    /**
     * Gathers the inheritable tags belonging to a resource's ancestors.
     *
     * @param resource the resource being described
     * @param definitions the known definitions, by tag name
     * @param found the occurrences gathered so far, updated in place
     */
    private void collectInherited(final Resource resource, final Map<String, TagDefinition> definitions,
        final Map<String, TagOccurrences> found)
    {
        for (Resource ancestor = resource.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            final Resource current = ancestor;
            ownTags(current).stream()
                .filter(name -> isInheritable(definitions.get(name)))
                .forEach(name -> record(found, name, Tag.Origin.INHERITED, current.getPath()));
        }
    }

    /**
     * Gathers the aggregated tags belonging to a resource's descendants, visiting the whole subtree.
     *
     * @param resource the resource being described
     * @param definitions the known definitions, by tag name
     * @param found the occurrences gathered so far, updated in place
     */
    private void collectAggregated(final Resource resource, final Map<String, TagDefinition> definitions,
        final Map<String, TagOccurrences> found)
    {
        final Deque<Resource> toVisit = new ArrayDeque<>();
        resource.getChildren().forEach(toVisit::add);
        while (!toVisit.isEmpty()) {
            final Resource descendant = toVisit.removeFirst();
            ownTags(descendant).stream()
                .filter(name -> isAggregated(definitions.get(name)))
                .forEach(name -> record(found, name, Tag.Origin.AGGREGATED, descendant.getPath()));
            descendant.getChildren().forEach(toVisit::add);
        }
    }

    private boolean isInheritable(final TagDefinition definition)
    {
        return definition != null && definition.isInheritable();
    }

    private boolean isAggregated(final TagDefinition definition)
    {
        return definition != null && definition.isAggregated();
    }

    /**
     * All the tags belonging to a resource itself, whether a user placed them explicitly or a tag processor computed
     * them from the resource's content. These are the tags that propagate to the resource's neighbors.
     *
     * @param resource the resource to read
     * @return the resource's own tag names, an empty set if it has none
     */
    private Set<String> ownTags(final Resource resource)
    {
        final Set<String> result = readTags(resource, TAGS_PROPERTY);
        result.addAll(readTags(resource, TagProcessor.Phase.LOCAL.getPropertyName()));
        return result;
    }

    private Set<String> readTags(final Resource resource, final String property)
    {
        final String[] tags = resource.getValueMap().get(property, String[].class);
        final Set<String> result = new LinkedHashSet<>();
        if (tags != null) {
            for (final String tag : tags) {
                result.add(tag);
            }
        }
        return result;
    }

    private void write(final Resource resource, final Set<String> tags) throws PersistenceException
    {
        final ModifiableValueMap values = resource.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            throw new PersistenceException("The resource " + resource.getPath() + " cannot be modified");
        }
        values.put(TAGS_PROPERTY, tags.toArray(new String[0]));
    }
}
