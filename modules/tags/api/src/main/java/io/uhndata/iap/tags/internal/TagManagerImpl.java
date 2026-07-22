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
import java.util.Set;
import java.util.function.BiConsumer;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import io.uhndata.iap.tags.api.Tag;
import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.models.TagDefinition;
import io.uhndata.iap.tags.spi.TagProcessor;

/**
 * Default implementation of {@link TagManager}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = TagManager.class)
public class TagManagerImpl implements TagManager
{
    /** All the registered tag processors, whose materialized properties contribute effective tags. */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        fieldOption = FieldOption.REPLACE)
    private volatile List<TagProcessor> tagProcessors;

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

    @Override
    public List<TagDefinition> getDefinitions(final ResourceResolver resolver)
    {
        final List<TagDefinition> result = new ArrayList<>();
        final Resource homepage = resolver.getResource(DEFINITIONS_PATH);
        if (homepage != null) {
            for (final Resource child : homepage.getChildren()) {
                if (!child.isResourceType(TagDefinition.RESOURCE_TYPE)) {
                    continue;
                }
                final TagDefinition definition = child.adaptTo(TagDefinition.class);
                if (definition != null) {
                    result.add(definition);
                }
            }
        }
        result.sort(TagDefinition.DISPLAY_ORDER);
        return result;
    }

    @Override
    public TagDefinition getDefinition(final ResourceResolver resolver, final String name)
    {
        return getDefinitions(resolver).stream()
            .filter(definition -> definition.getName().equals(name))
            .findFirst().orElse(null);
    }

    @Override
    public List<TagDefinition> findDefinitions(final ResourceResolver resolver, final String category,
        final String query)
    {
        return getDefinitions(resolver).stream()
            .filter(definition -> matchesCategory(definition, category))
            .filter(definition -> matchesQuery(definition, query))
            .toList();
    }

    @Override
    public List<TagDefinition> getApplicableDefinitions(final Resource resource)
    {
        return getDefinitions(resource.getResourceResolver()).stream()
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
        final List<TagProcessor> processors = this.tagProcessors;
        if (processors != null) {
            for (final TagProcessor processor : processors) {
                result.addAll(readTags(resource, processor.getPropertyName()));
            }
        }
        return result;
    }

    @Override
    public Collection<Tag> getEffectiveTags(final Resource resource)
    {
        final Map<String, TagDefinition> definitions = new HashMap<>();
        getDefinitions(resource.getResourceResolver())
            .forEach(definition -> definitions.put(definition.getName(), definition));
        final Map<String, TagOccurrences> found = new LinkedHashMap<>();
        final BiConsumer<String, Tag.Origin> add = (name, origin) -> {
            final TagOccurrences occurrences = found.computeIfAbsent(name, key -> new TagOccurrences());
            occurrences.origins.add(origin);
        };

        // The resource's own tags, whether defined or not
        for (final String name : getTags(resource)) {
            add.accept(name, Tag.Origin.EXPLICIT);
            found.get(name).sources.add(resource.getPath());
        }
        // Inheritable tags placed on ancestors
        for (Resource ancestor = resource.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            for (final String name : getTags(ancestor)) {
                final TagDefinition definition = definitions.get(name);
                if (definition != null && definition.isInheritable()) {
                    add.accept(name, Tag.Origin.INHERITED);
                    found.get(name).sources.add(ancestor.getPath());
                }
            }
        }
        // Aggregated tags placed on descendants
        final Deque<Resource> toVisit = new ArrayDeque<>();
        resource.getChildren().forEach(toVisit::add);
        while (!toVisit.isEmpty()) {
            final Resource descendant = toVisit.removeFirst();
            for (final String name : getTags(descendant)) {
                final TagDefinition definition = definitions.get(name);
                if (definition != null && definition.isAggregated()) {
                    add.accept(name, Tag.Origin.AGGREGATED);
                    found.get(name).sources.add(descendant.getPath());
                }
            }
            descendant.getChildren().forEach(toVisit::add);
        }

        final List<Tag> result = new ArrayList<>();
        found.forEach((name, occurrences) ->
            result.add(new Tag(name, definitions.get(name), occurrences.origins, occurrences.sources)));
        return result;
    }

    @Override
    public boolean hasTag(final Resource resource, final String name)
    {
        if (hasOwnTag(resource, name)) {
            return true;
        }
        final TagDefinition definition = getDefinition(resource.getResourceResolver(), name);
        if (definition == null) {
            return false;
        }
        if (definition.isInheritable()) {
            for (Resource ancestor = resource.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                if (hasOwnTag(ancestor, name)) {
                    return true;
                }
            }
        }
        if (definition.isAggregated()) {
            final Deque<Resource> toVisit = new ArrayDeque<>();
            resource.getChildren().forEach(toVisit::add);
            while (!toVisit.isEmpty()) {
                final Resource descendant = toVisit.removeFirst();
                if (hasOwnTag(descendant, name)) {
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
        checkRemovable(resource, name, allowSystem);
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
                checkRemovable(resource, name, allowSystem);
            }
        }
        write(resource, target);
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
        final TagDefinition definition = getDefinition(resource.getResourceResolver(), name);
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
     * @param resource the resource to untag
     * @param name the tag name
     * @param allowSystem whether system tags are allowed
     * @throws IllegalArgumentException if the tag may not be removed
     */
    private void checkRemovable(final Resource resource, final String name, final boolean allowSystem)
    {
        checkSystem(getDefinition(resource.getResourceResolver(), name), allowSystem);
    }

    private void checkSystem(final TagDefinition definition, final boolean allowSystem)
    {
        if (definition != null && definition.isSystem() && !allowSystem) {
            throw new IllegalArgumentException(
                "Tag " + definition.getName() + " is managed by the platform and cannot be manually changed");
        }
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
