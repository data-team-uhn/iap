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
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

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
import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.models.TagDefinition;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.tags.spi.TagProcessor.Phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

    private static final String INCOMPLETE = "incomplete";

    /** Marks a test resource whose stand-in node answers to the boundary mixin. */
    private static final String BOUNDARY = "testBoundary";

    /** Marks a test resource whose stand-in node fails to answer at all. */
    private static final String UNCLASSIFIABLE = "testUnclassifiable";

    private final SlingContext context = new SlingContext();

    private TagManagerImpl tagManager;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        this.context.addModelsForClasses(Content.class, TagDefinition.class, Taggable.class);
        this.context.create().resource("/Tags",
            TYPE_PROPERTY, "tag/Homepage");
        this.context.create().resource("/Tags/draft", Map.of(
            TYPE_PROPERTY, "tag/Definition",
            "label", "Draft",
            "description", "Work in progress",
            "category", new String[] { "lifecycle" },
            "order", 1L));
        this.context.create().resource("/Tags/submitted", Map.of(
            TYPE_PROPERTY, "tag/Definition",
            "category", new String[] { "lifecycle" },
            "system", true,
            "order", 2L));
        this.context.create().resource("/Tags/incomplete", Map.of(
            TYPE_PROPERTY, "tag/Definition",
            "category", new String[] { "validation" },
            "aggregated", true,
            "order", 3L));
        this.context.create().resource("/Tags/sensitive", Map.of(
            TYPE_PROPERTY, "tag/Definition",
            "category", new String[] { "privacy" },
            "inheritable", true,
            "targetResourceTypes", new String[] { "data/Entity" },
            "order", 4L));
        this.context.create().resource("/Tags/patientSurvey", Map.of(
            TYPE_PROPERTY, "tag/Definition",
            "name", "PATIENT SURVEY"));
        // An extensibility child of another type, not a tag definition
        this.context.create().resource("/Tags/config",
            TYPE_PROPERTY, "data/Content");
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
        assertEquals(List.of(DRAFT, "submitted", INCOMPLETE, SENSITIVE, "PATIENT SURVEY"),
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
            TYPE_PROPERTY, "data/EntityPart");
        // All unrestricted tags apply, the entity-only SENSITIVE tag does not
        assertEquals(List.of(DRAFT, "submitted", INCOMPLETE, "PATIENT SURVEY"),
            this.taggable(part).getApplicableDefinitions()
                .stream().map(TagDefinition::getName).toList());
    }

    @Test
    void readsOwnTags()
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "data/Entity",
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
            TYPE_PROPERTY, "data/Entity");

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
            TYPE_PROPERTY, "data/Entity");
        assertThrows(IllegalArgumentException.class, () -> this.taggable(resource).tag("unknown"));
    }

    @Test
    void rejectsInapplicableTags()
    {
        final Resource part = this.context.create().resource("/data/part",
            TYPE_PROPERTY, "data/EntityPart");
        // The SENSITIVE tag may only be placed on data/Entity resources
        assertThrows(IllegalArgumentException.class, () -> this.taggable(part).tag(SENSITIVE));
    }

    @Test
    void rejectsSystemTagsUnlessAllowed() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity",
            TYPE_PROPERTY, "data/Entity");

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
            TYPE_PROPERTY, "data/Entity",
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
            TYPE_PROPERTY, "data/Entity",
            "tags", new String[] { DRAFT }));

        this.taggable(resource).setTags(List.of(SENSITIVE, INCOMPLETE));
        assertEquals(Set.of(SENSITIVE, INCOMPLETE), this.taggable(resource).getTags());
    }

    @Test
    void replacingTagsWithTheSameSetIsANoOp() throws PersistenceException
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "data/Entity",
            // "submitted" is a system tag, but an unchanged set is not validated since nothing is added or removed
            "tags", new String[] { DRAFT, "submitted" }));

        this.taggable(resource).setTags(List.of("submitted", DRAFT));
        assertEquals(Set.of(DRAFT, "submitted"), this.taggable(resource).getTags());
    }

    @Test
    void readsEffectiveTagNamesFromEveryPhaseProperty()
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "data/Entity",
            "tags", new String[] { DRAFT },
            Phase.BOTTOM_UP.getPropertyName(), new String[] { INCOMPLETE },
            Phase.TOP_DOWN.getPropertyName(), new String[] { SENSITIVE },
            Phase.LOCAL.getPropertyName(), new String[] { "computed" }));

        // One property per phase, all read whatever processors happen to be registered
        assertEquals(Set.of(DRAFT, INCOMPLETE, SENSITIVE, "computed"),
            this.taggable(resource).getEffectiveTagNames());
    }

    @Test
    void effectiveTagNamesOfAnUntouchedNodeAreJustTheExplicitTags()
    {
        final Resource resource = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "data/Entity",
            "tags", new String[] { DRAFT }));

        assertEquals(Set.of(DRAFT), this.taggable(resource).getEffectiveTagNames());
    }

    @Test
    void locallyComputedTagsAreOwnTagsWithTheirOwnOrigin()
    {
        final Resource entity = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "data/Entity",
            "tags", new String[] { DRAFT },
            Phase.LOCAL.getPropertyName(), new String[] { SENSITIVE }));
        final Resource part = this.context.create().resource("/data/entity/part",
            TYPE_PROPERTY, "data/EntityPart");

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
            TYPE_PROPERTY, "data/Entity");
        this.context.create().resource("/data/entity/part", Map.of(
            TYPE_PROPERTY, "data/EntityPart",
            Phase.LOCAL.getPropertyName(), new String[] { INCOMPLETE }));

        assertTrue(this.taggable(entity).hasTag(INCOMPLETE));
        assertEquals(Set.of(Tag.Origin.AGGREGATED),
            collect(this.taggable(entity).getEffectiveTags()).get(INCOMPLETE).getOrigins());
    }

    /**
     * What a resource's tags say has to match what the propagation editor stored, so this read path stops at a
     * boundary exactly as the {@code aggregatedTags} chain does. Otherwise the two ways of asking give different
     * answers about the same content.
     */
    @Test
    void aggregationStopsAtABoundaryContainer()
    {
        this.context.registerAdapter(Resource.class, Node.class,
            (Function<Resource, Node>) TagManagerImplTest::asNode);
        final Resource entity = this.context.create().resource("/data/entity", TYPE_PROPERTY, "data/Entity");
        // A listing inside the entity, and content of its own carrying the aggregated tag
        this.context.create().resource("/data/entity/listing", Map.of(
            TYPE_PROPERTY, "data/EntityHomepage",
            BOUNDARY, true));
        this.context.create().resource("/data/entity/listing/inner", Map.of(
            TYPE_PROPERTY, "data/EntityPart",
            Phase.LOCAL.getPropertyName(), new String[] { INCOMPLETE }));
        // ...and ordinary content of the entity, which does aggregate
        this.context.create().resource("/data/entity/part", Map.of(
            TYPE_PROPERTY, "data/EntityPart",
            Phase.LOCAL.getPropertyName(), new String[] { INCOMPLETE }));

        final Tag aggregated = collect(this.taggable(entity).getEffectiveTags()).get(INCOMPLETE);

        assertEquals(Set.of(Tag.Origin.AGGREGATED), aggregated.getOrigins());
        assertEquals(Set.of("/data/entity/part"), aggregated.getSources());
    }

    @Test
    void aResourceThatCannotBeClassifiedCountsAsOrdinaryContent()
    {
        this.context.registerAdapter(Resource.class, Node.class,
            (Function<Resource, Node>) TagManagerImplTest::asNode);
        final Resource entity = this.context.create().resource("/data/entity", TYPE_PROPERTY, "data/Entity");
        this.context.create().resource("/data/entity/unclassifiable", Map.of(
            TYPE_PROPERTY, "data/EntityPart",
            UNCLASSIFIABLE, true,
            Phase.LOCAL.getPropertyName(), new String[] { INCOMPLETE }));

        // Describing a resource must not fail because one node could not be classified
        assertEquals(Set.of("/data/entity/unclassifiable"),
            collect(this.taggable(entity).getEffectiveTags()).get(INCOMPLETE).getSources());
    }

    /**
     * Stands in for the node behind a resource, since the mock resolver has no node types of its own: a marker
     * property decides what {@code isNodeType} answers, and another makes it fail the way a real one can.
     *
     * @param resource the resource being adapted
     * @return a node answering only {@code isNodeType}
     */
    private static Node asNode(final Resource resource)
    {
        final boolean boundary = resource.getValueMap().get(BOUNDARY, false);
        final boolean unclassifiable = resource.getValueMap().get(UNCLASSIFIABLE, false);
        return mock(Node.class, invocation -> {
            if (!"isNodeType".equals(invocation.getMethod().getName())) {
                return null;
            }
            if (unclassifiable) {
                throw new RepositoryException("this stand-in has no node types");
            }
            return boundary && TagManager.BOUNDARY_MIXIN.equals(invocation.getArgument(0));
        });
    }

    @Test
    void rereadsTheDefinitionsWhenTheyChange()
    {
        assertNull(this.tagManager.getDefinition("added"));

        this.context.create().resource("/Tags/added", Map.of(
            TYPE_PROPERTY, "tag/Definition",
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
            TYPE_PROPERTY, "data/Entity");
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
            TYPE_PROPERTY, "data/Entity",
            "tags", new String[] { "submitted", DRAFT }));

        // Adding an undefined tag is rejected
        assertThrows(IllegalArgumentException.class,
            () -> this.taggable(resource).setTags(List.of("submitted", "unknown")));
        // Dropping the system tag "submitted" is rejected
        assertThrows(IllegalArgumentException.class,
            () -> this.taggable(resource).setTags(List.of(DRAFT)));
        // Keeping the system tag while changing the others is fine
        this.taggable(resource).setTags(List.of("submitted", INCOMPLETE));
        assertEquals(Set.of("submitted", INCOMPLETE), this.taggable(resource).getTags());
        // With the platform-reserved variant, system tags may be dropped too
        this.taggable(resource).setTags(List.of(DRAFT), true);
        assertEquals(Set.of(DRAFT), this.taggable(resource).getTags());
    }

    @Test
    void computesEffectiveTags() throws PersistenceException
    {
        final Resource entity = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "data/Entity",
            "tags", new String[] { SENSITIVE, "legacy" }));
        final Resource part = this.context.create().resource("/data/entity/part", Map.of(
            TYPE_PROPERTY, "data/EntityPart",
            "tags", new String[] { DRAFT }));
        this.context.create().resource("/data/entity/part/answer", Map.of(
            TYPE_PROPERTY, "data/EntityPart",
            "tags", new String[] { INCOMPLETE }));

        // The entity carries its own tags, plus INCOMPLETE aggregated from a descendant;
        // the non-aggregated DRAFT on the part does not bubble up
        final Map<String, Tag> entityTags = collect(this.taggable(entity).getEffectiveTags());
        assertEquals(Set.of(SENSITIVE, "legacy", INCOMPLETE), entityTags.keySet());
        assertEquals(Set.of(Tag.Origin.EXPLICIT), entityTags.get(SENSITIVE).getOrigins());
        assertEquals(Set.of(Tag.Origin.AGGREGATED), entityTags.get(INCOMPLETE).getOrigins());
        assertEquals(Set.of("/data/entity/part/answer"), entityTags.get(INCOMPLETE).getSources());
        // The undefined "legacy" tag is still reported, without a definition
        assertFalse(entityTags.get("legacy").isDefined());
        assertTrue(entityTags.get(SENSITIVE).isDefined());

        // The part carries its own DRAFT, SENSITIVE inherited from the entity, and INCOMPLETE
        // aggregated from its own descendant; the non-inheritable "legacy" does not flow down
        final Map<String, Tag> partTags = collect(this.taggable(part).getEffectiveTags());
        assertEquals(Set.of(DRAFT, SENSITIVE, INCOMPLETE), partTags.keySet());
        assertEquals(Set.of(Tag.Origin.INHERITED), partTags.get(SENSITIVE).getOrigins());
        assertEquals(Set.of("/data/entity"), partTags.get(SENSITIVE).getSources());
    }

    @Test
    void combinesOriginsForTheSameTag()
    {
        this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "data/Entity",
            "tags", new String[] { SENSITIVE }));
        final Resource part = this.context.create().resource("/data/entity/part", Map.of(
            TYPE_PROPERTY, "data/EntityPart",
            "tags", new String[] { SENSITIVE }));

        final Map<String, Tag> tags = collect(this.taggable(part).getEffectiveTags());
        assertEquals(Set.of(Tag.Origin.EXPLICIT, Tag.Origin.INHERITED), tags.get(SENSITIVE).getOrigins());
        assertEquals(Set.of("/data/entity/part", "/data/entity"), tags.get(SENSITIVE).getSources());
    }

    @Test
    void checksEffectiveTags()
    {
        final Resource entity = this.context.create().resource("/data/entity", Map.of(
            TYPE_PROPERTY, "data/Entity",
            "tags", new String[] { SENSITIVE }));
        final Resource part = this.context.create().resource("/data/entity/part",
            TYPE_PROPERTY, "data/EntityPart");
        this.context.create().resource("/data/entity/part/answer", Map.of(
            TYPE_PROPERTY, "data/EntityPart",
            "tags", new String[] { INCOMPLETE, DRAFT }));

        // Explicit
        assertTrue(this.taggable(entity).hasTag(SENSITIVE));
        // Inherited from the entity
        assertTrue(this.taggable(part).hasTag(SENSITIVE));
        // Aggregated from the descendant answer
        assertTrue(this.taggable(entity).hasTag(INCOMPLETE));
        assertTrue(this.taggable(part).hasTag(INCOMPLETE));
        // DRAFT is neither inheritable nor aggregated, so it stays where it was placed
        assertFalse(this.taggable(entity).hasTag(DRAFT));
        assertFalse(this.taggable(part).hasTag(DRAFT));
        // Undefined tags are only carried explicitly
        assertFalse(this.taggable(entity).hasTag("unknown"));
        // Inheritable and aggregated tags placed nowhere near the resource are not carried either
        final Resource lonely = this.context.create().resource("/lonely",
            TYPE_PROPERTY, "data/Entity");
        assertFalse(this.taggable(lonely).hasTag(SENSITIVE));
        assertFalse(this.taggable(lonely).hasTag(INCOMPLETE));
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
