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

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.tags.api.Tag;
import io.uhndata.iap.tags.models.TagDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TagManagerImpl}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TagManagerImplTest
{
    private static final String TYPE_PROPERTY = "sling:resourceType";

    private static final String DRAFT = "draft";

    private static final String SENSITIVE = "sensitive";

    private final SlingContext context = new SlingContext();

    private final TagManagerImpl tagManager = new TagManagerImpl();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, TagDefinition.class);
        this.context.create().resource("/Tags",
            TYPE_PROPERTY, "iap/TagsHomepage");
        this.context.create().resource("/Tags/draft", Map.of(
            TYPE_PROPERTY, "iap/TagDefinition",
            "label", "Draft",
            "description", "Work in progress",
            "category", new String[] { "lifecycle" },
            "order", 1L));
        this.context.create().resource("/Tags/submitted", Map.of(
            TYPE_PROPERTY, "iap/TagDefinition",
            "category", new String[] { "lifecycle" },
            "system", true,
            "order", 2L));
        this.context.create().resource("/Tags/incomplete", Map.of(
            TYPE_PROPERTY, "iap/TagDefinition",
            "category", new String[] { "validation" },
            "aggregated", true,
            "order", 3L));
        this.context.create().resource("/Tags/sensitive", Map.of(
            TYPE_PROPERTY, "iap/TagDefinition",
            "category", new String[] { "privacy" },
            "inheritable", true,
            "targetResources", new String[] { "iap/Entity" },
            "order", 4L));
        this.context.create().resource("/Tags/patientSurvey", Map.of(
            TYPE_PROPERTY, "iap/TagDefinition",
            "name", "PATIENT SURVEY"));
        // An extensibility child of another type, not a tag definition
        this.context.create().resource("/Tags/config",
            TYPE_PROPERTY, "iap/Content");
    }

    @Test
    void listsDefinitionsInDisplayOrder()
    {
        assertEquals(List.of(DRAFT, "submitted", "incomplete", SENSITIVE, "PATIENT SURVEY"),
            this.tagManager.getDefinitions(this.context.resourceResolver())
                .stream().map(TagDefinition::getName).toList());
    }

    @Test
    void looksUpDefinitionsByName()
    {
        assertEquals("Draft",
            this.tagManager.getDefinition(this.context.resourceResolver(), DRAFT).getLabel());
        // Explicit name properties are honored, and the node name they override does not match
        assertNotNull(this.tagManager.getDefinition(this.context.resourceResolver(), "PATIENT SURVEY"));
        assertNull(this.tagManager.getDefinition(this.context.resourceResolver(), "patientSurvey"));
        assertNull(this.tagManager.getDefinition(this.context.resourceResolver(), "unknown"));
    }

    @Test
    void findsDefinitionsByCategory()
    {
        assertEquals(List.of(DRAFT, "submitted"),
            this.tagManager.findDefinitions(this.context.resourceResolver(), "LifeCycle", null)
                .stream().map(TagDefinition::getName).toList());
    }

    @Test
    void findsDefinitionsByText()
    {
        // The query matches names, labels and descriptions, ignoring case
        assertEquals(List.of(DRAFT),
            this.tagManager.findDefinitions(this.context.resourceResolver(), null, "PROGRESS")
                .stream().map(TagDefinition::getName).toList());
        assertEquals(List.of("PATIENT SURVEY"),
            this.tagManager.findDefinitions(this.context.resourceResolver(), null, "survey")
                .stream().map(TagDefinition::getName).toList());
    }

    @Test
    void combinesCategoryAndTextFilters()
    {
        assertEquals(List.of("submitted"),
            this.tagManager.findDefinitions(this.context.resourceResolver(), "lifecycle", "sub")
                .stream().map(TagDefinition::getName).toList());
        assertTrue(this.tagManager.findDefinitions(this.context.resourceResolver(), "privacy", DRAFT).isEmpty());
    }

    @Test
    void blankFiltersReturnAllDefinitions()
    {
        assertEquals(5, this.tagManager.findDefinitions(this.context.resourceResolver(), " ", "").size());
    }

    @Test
    void listsDefinitionsApplicableToResource()
    {
        final Resource part = this.context.create().resource("/data/part",
            TYPE_PROPERTY, "iap/EntityPart");
        // All unrestricted tags apply, the entity-only SENSITIVE tag does not
        assertEquals(List.of(DRAFT, "submitted", "incomplete", "PATIENT SURVEY"),
            this.tagManager.getApplicableDefinitions(part)
                .stream().map(TagDefinition::getName).toList());
    }

    @Test
    void readsOwnTags()
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { DRAFT, "legacy" }));

        assertEquals(Set.of(DRAFT, "legacy"), this.tagManager.getTags(resource));
        assertTrue(this.tagManager.hasOwnTag(resource, DRAFT));
        assertFalse(this.tagManager.hasOwnTag(resource, "submitted"));
        assertEquals(Set.of(),
            this.tagManager.getTags(this.context.create().resource("/data/untagged")));
    }

    @Test
    void addsTags() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity",
            TYPE_PROPERTY, "iap/Entity");

        assertTrue(this.tagManager.tag(resource, DRAFT));
        // Re-adding an already present tag is a no-op
        assertFalse(this.tagManager.tag(resource, DRAFT));
        assertTrue(this.tagManager.tag(resource, SENSITIVE));
        assertEquals(Set.of(DRAFT, SENSITIVE), this.tagManager.getTags(resource));
    }

    @Test
    void rejectsUndefinedTags()
    {
        final Resource resource = this.context.create().resource("/data/entity",
            TYPE_PROPERTY, "iap/Entity");
        assertThrows(IllegalArgumentException.class, () -> this.tagManager.tag(resource, "unknown"));
    }

    @Test
    void rejectsInapplicableTags()
    {
        final Resource part = this.context.create().resource("/data/part",
            TYPE_PROPERTY, "iap/EntityPart");
        // The SENSITIVE tag may only be placed on iap/Entity resources
        assertThrows(IllegalArgumentException.class, () -> this.tagManager.tag(part, SENSITIVE));
    }

    @Test
    void rejectsSystemTagsUnlessAllowed() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity",
            TYPE_PROPERTY, "iap/Entity");

        assertThrows(IllegalArgumentException.class, () -> this.tagManager.tag(resource, "submitted"));
        assertTrue(this.tagManager.tag(resource, "submitted", true));
        assertThrows(IllegalArgumentException.class, () -> this.tagManager.untag(resource, "submitted"));
        assertTrue(this.tagManager.untag(resource, "submitted", true));
        assertEquals(Set.of(), this.tagManager.getTags(resource));
    }

    @Test
    void removesTags() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { DRAFT, "legacy" }));

        assertTrue(this.tagManager.untag(resource, DRAFT));
        assertFalse(this.tagManager.untag(resource, DRAFT));
        // Undefined tags left behind, e.g. after their definition was deleted, can still be removed
        assertTrue(this.tagManager.untag(resource, "legacy"));
        assertEquals(Set.of(), this.tagManager.getTags(resource));
    }

    @Test
    void replacesTags() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { DRAFT }));

        this.tagManager.setTags(resource, List.of(SENSITIVE, "incomplete"));
        assertEquals(Set.of(SENSITIVE, "incomplete"), this.tagManager.getTags(resource));
    }

    @Test
    void replacingTagsWithTheSameSetIsANoOp() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            // "submitted" is a system tag, but an unchanged set is not validated since nothing is added or removed
            "tags", new String[] { DRAFT, "submitted" }));

        this.tagManager.setTags(resource, List.of("submitted", DRAFT));
        assertEquals(Set.of(DRAFT, "submitted"), this.tagManager.getTags(resource));
    }

    @Test
    void readsEffectiveTagNamesFromMaterializedProperties() throws Exception
    {
        final Field reference = TagManagerImpl.class.getDeclaredField("tagProcessors");
        reference.setAccessible(true);
        reference.set(this.tagManager, List.of(new TagAggregationProcessor(), new TagInheritanceProcessor()));
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { DRAFT },
            TagAggregationProcessor.PROPERTY, new String[] { "incomplete" },
            TagInheritanceProcessor.PROPERTY, new String[] { SENSITIVE }));

        assertEquals(Set.of(DRAFT, "incomplete", SENSITIVE), this.tagManager.getEffectiveTagNames(resource));
    }

    @Test
    void effectiveTagNamesWithoutProcessorsAreJustTheExplicitTags()
    {
        // Without any registered tag processors there are no materialized properties to read
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { DRAFT },
            TagAggregationProcessor.PROPERTY, new String[] { "incomplete" }));

        assertEquals(Set.of(DRAFT), this.tagManager.getEffectiveTagNames(resource));
    }

    @Test
    void reportsUnmodifiableResources()
    {
        final Resource resource = this.context.create().resource("/data/entity",
            TYPE_PROPERTY, "iap/Entity");
        final Resource readOnly = new ResourceWrapper(resource)
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == ModifiableValueMap.class ? null : super.adaptTo(type);
            }
        };

        assertThrows(PersistenceException.class, () -> this.tagManager.tag(readOnly, DRAFT));
    }

    @Test
    void replacingTagsValidatesAdditionsAndRemovals() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { "submitted", DRAFT }));

        // Adding an undefined tag is rejected
        assertThrows(IllegalArgumentException.class,
            () -> this.tagManager.setTags(resource, List.of("submitted", "unknown")));
        // Dropping the system tag "submitted" is rejected
        assertThrows(IllegalArgumentException.class,
            () -> this.tagManager.setTags(resource, List.of(DRAFT)));
        // Keeping the system tag while changing the others is fine
        this.tagManager.setTags(resource, List.of("submitted", "incomplete"));
        assertEquals(Set.of("submitted", "incomplete"), this.tagManager.getTags(resource));
        // With the platform-reserved variant, system tags may be dropped too
        this.tagManager.setTags(resource, List.of(DRAFT), true);
        assertEquals(Set.of(DRAFT), this.tagManager.getTags(resource));
    }

    @Test
    void computesEffectiveTags() throws PersistenceException
    {
        final Resource entity = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { SENSITIVE, "legacy" }));
        final Resource part = this.context.create().resource("/data/entity/part", Map.of(
            TYPE_PROPERTY, "iap/EntityPart",
            "tags", new String[] { DRAFT }));
        this.context.create().resource("/data/entity/part/answer", Map.of(
            TYPE_PROPERTY, "iap/EntityPart",
            "tags", new String[] { "incomplete" }));

        // The entity carries its own tags, plus "incomplete" aggregated from a descendant;
        // the non-aggregated DRAFT on the part does not bubble up
        final Map<String, Tag> entityTags = collect(this.tagManager.getEffectiveTags(entity));
        assertEquals(Set.of(SENSITIVE, "legacy", "incomplete"), entityTags.keySet());
        assertEquals(Set.of(Tag.Origin.EXPLICIT), entityTags.get(SENSITIVE).getOrigins());
        assertEquals(Set.of(Tag.Origin.AGGREGATED), entityTags.get("incomplete").getOrigins());
        assertEquals(Set.of("/data/entity/part/answer"), entityTags.get("incomplete").getSources());
        // The undefined "legacy" tag is still reported, without a definition
        assertFalse(entityTags.get("legacy").isDefined());
        assertTrue(entityTags.get(SENSITIVE).isDefined());

        // The part carries its own DRAFT, SENSITIVE inherited from the entity, and "incomplete"
        // aggregated from its own descendant; the non-inheritable "legacy" does not flow down
        final Map<String, Tag> partTags = collect(this.tagManager.getEffectiveTags(part));
        assertEquals(Set.of(DRAFT, SENSITIVE, "incomplete"), partTags.keySet());
        assertEquals(Set.of(Tag.Origin.INHERITED), partTags.get(SENSITIVE).getOrigins());
        assertEquals(Set.of("/data/entity"), partTags.get(SENSITIVE).getSources());
    }

    @Test
    void combinesOriginsForTheSameTag()
    {
        this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { SENSITIVE }));
        final Resource part = this.context.create().resource("/data/entity/part", Map.of(
            TYPE_PROPERTY, "iap/EntityPart",
            "tags", new String[] { SENSITIVE }));

        final Map<String, Tag> tags = collect(this.tagManager.getEffectiveTags(part));
        assertEquals(Set.of(Tag.Origin.EXPLICIT, Tag.Origin.INHERITED), tags.get(SENSITIVE).getOrigins());
        assertEquals(Set.of("/data/entity/part", "/data/entity"), tags.get(SENSITIVE).getSources());
    }

    @Test
    void checksEffectiveTags()
    {
        final Resource entity = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { SENSITIVE }));
        final Resource part = this.context.create().resource("/data/entity/part",
            TYPE_PROPERTY, "iap/EntityPart");
        this.context.create().resource("/data/entity/part/answer", Map.of(
            TYPE_PROPERTY, "iap/EntityPart",
            "tags", new String[] { "incomplete", DRAFT }));

        // Explicit
        assertTrue(this.tagManager.hasTag(entity, SENSITIVE));
        // Inherited from the entity
        assertTrue(this.tagManager.hasTag(part, SENSITIVE));
        // Aggregated from the descendant answer
        assertTrue(this.tagManager.hasTag(entity, "incomplete"));
        assertTrue(this.tagManager.hasTag(part, "incomplete"));
        // DRAFT is neither inheritable nor aggregated, so it stays where it was placed
        assertFalse(this.tagManager.hasTag(entity, DRAFT));
        assertFalse(this.tagManager.hasTag(part, DRAFT));
        // Undefined tags are only carried explicitly
        assertFalse(this.tagManager.hasTag(entity, "unknown"));
        // Inheritable and aggregated tags placed nowhere near the resource are not carried either
        final Resource lonely = this.context.create().resource("/lonely",
            TYPE_PROPERTY, "iap/Entity");
        assertFalse(this.tagManager.hasTag(lonely, SENSITIVE));
        assertFalse(this.tagManager.hasTag(lonely, "incomplete"));
    }

    private Map<String, Tag> collect(final Iterable<Tag> tags)
    {
        final Map<String, Tag> result = new HashMap<>();
        tags.forEach(tag -> result.put(tag.getName(), tag));
        return result;
    }
}
