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
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.tags.api.Tag;
import io.uhndata.iap.tags.models.TagDefinition;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.tags.spi.TagProcessor.Phase;

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

    private TagManagerImpl tagManager;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        this.context.addModelsForClasses(Content.class, TagDefinition.class, Taggable.class);
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
            "targetResourceTypes", new String[] { "iap/Entity" },
            "order", 4L));
        this.context.create().resource("/Tags/patientSurvey", Map.of(
            TYPE_PROPERTY, "iap/TagDefinition",
            "name", "PATIENT SURVEY"));
        // An extensibility child of another type, not a tag definition
        this.context.create().resource("/Tags/config",
            TYPE_PROPERTY, "iap/Content");
        // The manager reads the definitions with its own service user, so it needs a resolver factory; the SCR
        // metadata that would let the mock OSGi runtime inject it is only generated when the bundle is packaged
        this.tagManager = new TagManagerImpl();
        final Field factory = TagManagerImpl.class.getDeclaredField("resolverFactory");
        factory.setAccessible(true);
        factory.set(this.tagManager, new TestResolverFactory(this.context.resourceResolver()));
        // The models' behavior delegates to the manager through its internal TagOperations face
        this.context.registerService(TagOperations.class, this.tagManager);
    }

    @Test
    void listsDefinitionsInDisplayOrder()
    {
        assertEquals(List.of(DRAFT, "submitted", "incomplete", SENSITIVE, "PATIENT SURVEY"),
            this.tagManager.getDefinitions()
                .stream().map(TagDefinition::getName).toList());
    }

    @Test
    void looksUpDefinitionsByName()
    {
        assertEquals("Draft",
            this.tagManager.getDefinition(DRAFT).getLabel());
        // Explicit name properties are honored, and the node name they override does not match
        assertNotNull(this.tagManager.getDefinition("PATIENT SURVEY"));
        assertNull(this.tagManager.getDefinition("patientSurvey"));
        assertNull(this.tagManager.getDefinition("unknown"));
    }

    @Test
    void findsDefinitionsByCategory()
    {
        assertEquals(List.of(DRAFT, "submitted"),
            this.tagManager.findDefinitions("LifeCycle", null)
                .stream().map(TagDefinition::getName).toList());
    }

    @Test
    void findsDefinitionsByText()
    {
        // The query matches names, labels and descriptions, ignoring case
        assertEquals(List.of(DRAFT),
            this.tagManager.findDefinitions(null, "PROGRESS")
                .stream().map(TagDefinition::getName).toList());
        assertEquals(List.of("PATIENT SURVEY"),
            this.tagManager.findDefinitions(null, "survey")
                .stream().map(TagDefinition::getName).toList());
    }

    @Test
    void combinesCategoryAndTextFilters()
    {
        assertEquals(List.of("submitted"),
            this.tagManager.findDefinitions("lifecycle", "sub")
                .stream().map(TagDefinition::getName).toList());
        assertTrue(this.tagManager.findDefinitions("privacy", DRAFT).isEmpty());
    }

    @Test
    void blankFiltersReturnAllDefinitions()
    {
        assertEquals(5, this.tagManager.findDefinitions(" ", "").size());
    }

    @Test
    void listsDefinitionsApplicableToResource()
    {
        final Resource part = this.context.create().resource("/data/part",
            TYPE_PROPERTY, "iap/EntityPart");
        // All unrestricted tags apply, the entity-only SENSITIVE tag does not
        assertEquals(List.of(DRAFT, "submitted", "incomplete", "PATIENT SURVEY"),
            this.taggable(part).getApplicableDefinitions()
                .stream().map(TagDefinition::getName).toList());
    }

    @Test
    void readsOwnTags()
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { DRAFT, "legacy" }));

        assertEquals(Set.of(DRAFT, "legacy"), this.taggable(resource).getTags());
        assertTrue(this.taggable(resource).hasOwnTag(DRAFT));
        assertFalse(this.taggable(resource).hasOwnTag("submitted"));
        assertEquals(Set.of(),
            this.taggable(this.context.create().resource("/data/untagged")).getTags());
    }

    @Test
    void addsTags() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity",
            TYPE_PROPERTY, "iap/Entity");

        assertTrue(this.taggable(resource).tag(DRAFT));
        // Re-adding an already present tag is a no-op
        assertFalse(this.taggable(resource).tag(DRAFT));
        assertTrue(this.taggable(resource).tag(SENSITIVE));
        assertEquals(Set.of(DRAFT, SENSITIVE), this.taggable(resource).getTags());
    }

    @Test
    void rejectsUndefinedTags()
    {
        final Resource resource = this.context.create().resource("/data/entity",
            TYPE_PROPERTY, "iap/Entity");
        assertThrows(IllegalArgumentException.class, () -> this.taggable(resource).tag("unknown"));
    }

    @Test
    void rejectsInapplicableTags()
    {
        final Resource part = this.context.create().resource("/data/part",
            TYPE_PROPERTY, "iap/EntityPart");
        // The SENSITIVE tag may only be placed on iap/Entity resources
        assertThrows(IllegalArgumentException.class, () -> this.taggable(part).tag(SENSITIVE));
    }

    @Test
    void rejectsSystemTagsUnlessAllowed() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity",
            TYPE_PROPERTY, "iap/Entity");

        assertThrows(IllegalArgumentException.class, () -> this.taggable(resource).tag("submitted"));
        assertTrue(this.taggable(resource).tag("submitted", true));
        assertThrows(IllegalArgumentException.class, () -> this.taggable(resource).untag("submitted"));
        assertTrue(this.taggable(resource).untag("submitted", true));
        assertEquals(Set.of(), this.taggable(resource).getTags());
    }

    @Test
    void removesTags() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { DRAFT, "legacy" }));

        assertTrue(this.taggable(resource).untag(DRAFT));
        assertFalse(this.taggable(resource).untag(DRAFT));
        // Undefined tags left behind, e.g. after their definition was deleted, can still be removed
        assertTrue(this.taggable(resource).untag("legacy"));
        assertEquals(Set.of(), this.taggable(resource).getTags());
    }

    @Test
    void replacesTags() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { DRAFT }));

        this.taggable(resource).setTags(List.of(SENSITIVE, "incomplete"));
        assertEquals(Set.of(SENSITIVE, "incomplete"), this.taggable(resource).getTags());
    }

    @Test
    void replacingTagsWithTheSameSetIsANoOp() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            // "submitted" is a system tag, but an unchanged set is not validated since nothing is added or removed
            "tags", new String[] { DRAFT, "submitted" }));

        this.taggable(resource).setTags(List.of("submitted", DRAFT));
        assertEquals(Set.of(DRAFT, "submitted"), this.taggable(resource).getTags());
    }

    @Test
    void readsEffectiveTagNamesFromEveryPhaseProperty()
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { DRAFT },
            Phase.BOTTOM_UP.getPropertyName(), new String[] { "incomplete" },
            Phase.TOP_DOWN.getPropertyName(), new String[] { SENSITIVE },
            Phase.LOCAL.getPropertyName(), new String[] { "computed" }));

        // One property per phase, all read whatever processors happen to be registered
        assertEquals(Set.of(DRAFT, "incomplete", SENSITIVE, "computed"),
            this.taggable(resource).getEffectiveTagNames());
    }

    @Test
    void effectiveTagNamesOfAnUntouchedNodeAreJustTheExplicitTags()
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { DRAFT }));

        assertEquals(Set.of(DRAFT), this.taggable(resource).getEffectiveTagNames());
    }

    @Test
    void locallyComputedTagsAreOwnTagsWithTheirOwnOrigin()
    {
        final Resource entity = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { DRAFT },
            Phase.LOCAL.getPropertyName(), new String[] { SENSITIVE }));
        final Resource part = this.context.create().resource("/data/entity/part",
            TYPE_PROPERTY, "iap/EntityPart");

        final Map<String, Tag> entityTags = collect(this.taggable(entity).getEffectiveTags());
        assertEquals(Set.of(Tag.Origin.COMPUTED), entityTags.get(SENSITIVE).getOrigins());
        assertEquals(Set.of(Tag.Origin.EXPLICIT), entityTags.get(DRAFT).getOrigins());
        // A computed tag propagates exactly like an explicitly placed one
        assertEquals(Set.of(Tag.Origin.INHERITED),
            collect(this.taggable(part).getEffectiveTags()).get(SENSITIVE).getOrigins());
        assertTrue(this.taggable(part).hasTag(SENSITIVE));
        // ...but it is not an explicit tag of the node it was computed for
        assertFalse(this.taggable(entity).hasOwnTag(SENSITIVE));
        assertTrue(this.taggable(entity).hasTag(SENSITIVE));
    }

    @Test
    void aggregatesLocallyComputedTagsOfDescendants()
    {
        final Resource entity = this.context.create().resource("/data/entity",
            TYPE_PROPERTY, "iap/Entity");
        this.context.create().resource("/data/entity/part", Map.of(
            TYPE_PROPERTY, "iap/EntityPart",
            Phase.LOCAL.getPropertyName(), new String[] { "incomplete" }));

        assertTrue(this.taggable(entity).hasTag("incomplete"));
        assertEquals(Set.of(Tag.Origin.AGGREGATED),
            collect(this.taggable(entity).getEffectiveTags()).get("incomplete").getOrigins());
    }

    @Test
    void rereadsTheDefinitionsWhenTheyChange()
    {
        assertNull(this.tagManager.getDefinition("added"));

        this.context.create().resource("/Tags/added", Map.of(
            TYPE_PROPERTY, "iap/TagDefinition",
            "label", "Added"));
        // The definitions are cached until something under /Tags changes
        assertNull(this.tagManager.getDefinition("added"));
        this.tagManager.onChange(List.of());

        assertEquals("Added", this.tagManager.getDefinition("added").getLabel());
    }

    @Test
    void survivesAMissingServiceUser() throws ReflectiveOperationException
    {
        final TagManagerImpl manager = new TagManagerImpl();
        final Field factory = TagManagerImpl.class.getDeclaredField("resolverFactory");
        factory.setAccessible(true);
        factory.set(manager, new TestResolverFactory(null));

        // A misconfigured deployment must not take every tag lookup down with it
        assertTrue(manager.getDefinitions().isEmpty());
        assertNull(manager.getDefinition(DRAFT));
    }

    @Test
    void releasesItsResolverWhenStopped()
    {
        assertFalse(this.tagManager.getDefinitions().isEmpty());
        this.tagManager.deactivate();

        // The definitions are read again, with a fresh resolver, if the manager is used after being stopped
        assertFalse(this.tagManager.getDefinitions().isEmpty());
    }

    @Test
    void survivesAMissingDefinitionsHomepage() throws PersistenceException
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        resolver.delete(resolver.getResource("/Tags"));
        resolver.commit();
        this.tagManager.onChange(List.of());

        assertTrue(this.tagManager.getDefinitions().isEmpty());
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

        // Through the internal face: adapting the wrapper to a model would slip past its refusal, since model
        // adaptation delegates to the wrapped, writable resource
        assertThrows(PersistenceException.class, () -> this.tagManager.tag(readOnly, DRAFT, false));
    }

    @Test
    void replacingTagsValidatesAdditionsAndRemovals() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "iap/Entity",
            "tags", new String[] { "submitted", DRAFT }));

        // Adding an undefined tag is rejected
        assertThrows(IllegalArgumentException.class,
            () -> this.taggable(resource).setTags(List.of("submitted", "unknown")));
        // Dropping the system tag "submitted" is rejected
        assertThrows(IllegalArgumentException.class,
            () -> this.taggable(resource).setTags(List.of(DRAFT)));
        // Keeping the system tag while changing the others is fine
        this.taggable(resource).setTags(List.of("submitted", "incomplete"));
        assertEquals(Set.of("submitted", "incomplete"), this.taggable(resource).getTags());
        // With the platform-reserved variant, system tags may be dropped too
        this.taggable(resource).setTags(List.of(DRAFT), true);
        assertEquals(Set.of(DRAFT), this.taggable(resource).getTags());
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
        final Map<String, Tag> entityTags = collect(this.taggable(entity).getEffectiveTags());
        assertEquals(Set.of(SENSITIVE, "legacy", "incomplete"), entityTags.keySet());
        assertEquals(Set.of(Tag.Origin.EXPLICIT), entityTags.get(SENSITIVE).getOrigins());
        assertEquals(Set.of(Tag.Origin.AGGREGATED), entityTags.get("incomplete").getOrigins());
        assertEquals(Set.of("/data/entity/part/answer"), entityTags.get("incomplete").getSources());
        // The undefined "legacy" tag is still reported, without a definition
        assertFalse(entityTags.get("legacy").isDefined());
        assertTrue(entityTags.get(SENSITIVE).isDefined());

        // The part carries its own DRAFT, SENSITIVE inherited from the entity, and "incomplete"
        // aggregated from its own descendant; the non-inheritable "legacy" does not flow down
        final Map<String, Tag> partTags = collect(this.taggable(part).getEffectiveTags());
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

        final Map<String, Tag> tags = collect(this.taggable(part).getEffectiveTags());
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
        assertTrue(this.taggable(entity).hasTag(SENSITIVE));
        // Inherited from the entity
        assertTrue(this.taggable(part).hasTag(SENSITIVE));
        // Aggregated from the descendant answer
        assertTrue(this.taggable(entity).hasTag("incomplete"));
        assertTrue(this.taggable(part).hasTag("incomplete"));
        // DRAFT is neither inheritable nor aggregated, so it stays where it was placed
        assertFalse(this.taggable(entity).hasTag(DRAFT));
        assertFalse(this.taggable(part).hasTag(DRAFT));
        // Undefined tags are only carried explicitly
        assertFalse(this.taggable(entity).hasTag("unknown"));
        // Inheritable and aggregated tags placed nowhere near the resource are not carried either
        final Resource lonely = this.context.create().resource("/lonely",
            TYPE_PROPERTY, "iap/Entity");
        assertFalse(this.taggable(lonely).hasTag(SENSITIVE));
        assertFalse(this.taggable(lonely).hasTag("incomplete"));
    }

    private Map<String, Tag> collect(final Iterable<Tag> tags)
    {
        final Map<String, Tag> result = new HashMap<>();
        tags.forEach(tag -> result.put(tag.getName(), tag));
        return result;
    }

    @Test
    void unadaptableNodesHaveNoApplicableDefinitions()
    {
        // A resource with no Content view, e.g. a synthetic one; real repository nodes always adapt
        final Resource opaque = new ResourceWrapper(this.context.create().resource("/data/opaque"))
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return null;
            }
        };
        assertTrue(this.tagManager.getApplicableDefinitions(opaque).isEmpty());
    }

    private Taggable taggable(final Resource resource)
    {
        return resource.adaptTo(Taggable.class);
    }
}
