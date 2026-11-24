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
package io.uhndata.iap.tags.testing;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.tags.models.Taggable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PropertyTagging}, the tags service as other modules' tests see it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class PropertyTaggingTest
{
    private static final String PATH = "/content/item";

    private static final String TAGS = "tags";

    private static final String DRAFT = "draft";

    private static final String SENSITIVE = "sensitive";

    private static final String APPROVED = "approved";

    private final SlingContext context = new SlingContext();

    @Test
    void readsTheTagsPlacedOnTheNode()
    {
        PropertyTagging.installIn(this.context);

        final Taggable taggable = taggable(DRAFT, SENSITIVE);

        assertEquals(Set.of(DRAFT, SENSITIVE), taggable.getTags());
        assertTrue(taggable.hasOwnTag(DRAFT));
        assertTrue(taggable.hasTag(SENSITIVE));
        assertFalse(taggable.hasOwnTag(APPROVED));
        assertFalse(taggable.hasTag(APPROVED));
    }

    @Test
    void placesATagBesideTheOthers() throws PersistenceException
    {
        PropertyTagging.installIn(this.context);
        final Taggable taggable = taggable(DRAFT);

        assertTrue(taggable.tag(SENSITIVE));
        assertTrue(taggable.tag("urgent", true));

        assertEquals(List.of(DRAFT, SENSITIVE, "urgent"), stored());
    }

    @Test
    void placingATagAlreadyThereChangesNothing() throws PersistenceException
    {
        PropertyTagging.installIn(this.context);
        final Taggable taggable = taggable(DRAFT);
        this.context.resourceResolver().commit();

        assertFalse(taggable.tag(DRAFT));

        assertFalse(this.context.resourceResolver().hasChanges());
    }

    @Test
    void leavesWritesInTheSession() throws PersistenceException
    {
        PropertyTagging.installIn(this.context);
        final Taggable taggable = taggable(DRAFT);
        this.context.resourceResolver().commit();

        taggable.tag(SENSITIVE);

        assertTrue(this.context.resourceResolver().hasChanges());
    }

    @Test
    void refusesToPlaceTheUndefinedTag()
    {
        PropertyTagging.installIn(this.context);
        final Taggable taggable = taggable();

        assertThrows(IllegalArgumentException.class, () -> taggable.tag(PropertyTagging.UNDEFINED));
        assertThrows(IllegalArgumentException.class, () -> taggable.tag(PropertyTagging.UNDEFINED, true));
        assertEquals(List.of(), stored());
    }

    @Test
    void refusesToPlaceTheTagsItIsToldAreUndefined()
    {
        PropertyTagging.builder().refusing("deleted").installIn(this.context);
        final Taggable taggable = taggable();

        assertThrows(IllegalArgumentException.class, () -> taggable.tag("deleted"));
        assertThrows(IllegalArgumentException.class, () -> taggable.tag(PropertyTagging.UNDEFINED));
    }

    @Test
    void removesATagEvenAnUndefinedOne() throws PersistenceException
    {
        PropertyTagging.installIn(this.context);
        final Taggable taggable = taggable(DRAFT, PropertyTagging.UNDEFINED, SENSITIVE);

        assertTrue(taggable.untag(PropertyTagging.UNDEFINED));
        assertTrue(taggable.untag(DRAFT, true));
        assertFalse(taggable.untag(APPROVED));

        assertEquals(List.of(SENSITIVE), stored());
    }

    @Test
    void replacingRetiresTheTagsSharingACategory() throws PersistenceException
    {
        PropertyTagging.builder()
            .inCategories(DRAFT, "lifecycle")
            .inCategories(APPROVED, "lifecycle", "review")
            .inCategories("flagged", "review")
            .installIn(this.context);
        final Taggable taggable = taggable(DRAFT, SENSITIVE, "flagged");

        assertTrue(taggable.tagReplacing(APPROVED, true));

        assertEquals(List.of(SENSITIVE, APPROVED), stored());
    }

    @Test
    void replacingWithATagOfNoCategoryRetiresNothing() throws PersistenceException
    {
        PropertyTagging.builder().inCategories(DRAFT, "lifecycle").installIn(this.context);
        final Taggable taggable = taggable(DRAFT);

        assertTrue(taggable.tagReplacing(SENSITIVE, true));
        assertFalse(taggable.tagReplacing(SENSITIVE, true));

        assertEquals(List.of(DRAFT, SENSITIVE), stored());
    }

    @Test
    void recordsTheReplacementsAskedFor() throws PersistenceException
    {
        final PropertyTagging tagging = PropertyTagging.installIn(this.context);
        final Taggable taggable = taggable();

        taggable.tagReplacing(APPROVED, true);
        taggable.tagReplacing(APPROVED, false);
        assertThrows(IllegalArgumentException.class, () -> taggable.tagReplacing(PropertyTagging.UNDEFINED, true));

        assertEquals(List.of(APPROVED, APPROVED), tagging.getReplacements());
    }

    @Test
    void givesTheReplacementsAsTheyWereWhenAsked() throws PersistenceException
    {
        final PropertyTagging tagging = PropertyTagging.installIn(this.context);
        final List<String> before = tagging.getReplacements();

        taggable().tagReplacing(APPROVED, true);

        assertEquals(List.of(), before);
    }

    @Test
    void setsTheTagsKeepingAnUndefinedOneAlreadyPlaced() throws PersistenceException
    {
        PropertyTagging.installIn(this.context);
        final Taggable taggable = taggable(DRAFT, PropertyTagging.UNDEFINED);

        taggable.setTags(List.of(PropertyTagging.UNDEFINED, APPROVED));
        taggable.setTags(List.of(APPROVED, SENSITIVE), true);

        assertEquals(List.of(APPROVED, SENSITIVE), stored());
    }

    @Test
    void refusesToSetAnUndefinedTag()
    {
        PropertyTagging.installIn(this.context);
        final Taggable taggable = taggable(DRAFT);

        assertThrows(IllegalArgumentException.class, () -> taggable.setTags(List.of(PropertyTagging.UNDEFINED)));
        assertEquals(List.of(DRAFT), stored());
    }

    @Test
    void findsAnInheritableTagPlacedOnAnAncestor()
    {
        PropertyTagging.builder().inheritable("retired").installIn(this.context);
        this.context.create().resource("/content", TAGS, new String[] {"retired", SENSITIVE});

        final Taggable taggable = taggable();

        assertTrue(taggable.hasTag("retired"));
        assertFalse(taggable.hasOwnTag("retired"));
        assertFalse(taggable.hasTag(SENSITIVE));
    }

    @Test
    void refusesWritesToAPathItIsToldToRefuse() throws PersistenceException
    {
        final PropertyTagging tagging = PropertyTagging.installIn(this.context);
        final Taggable taggable = taggable(DRAFT);
        tagging.refuseWritesTo(PATH);

        assertThrows(PersistenceException.class, () -> taggable.tag(SENSITIVE));
        assertFalse(taggable.untag(APPROVED));
        assertEquals(List.of(DRAFT), stored());
    }

    @Test
    void refusesWritesToAResourceThatCannotBeModified()
    {
        final PropertyTagging tagging = PropertyTagging.installIn(this.context);
        final Taggable taggable = tagging.viewOf(unmodifiable());

        assertThrows(PersistenceException.class, () -> taggable.tag(DRAFT));
    }

    @Test
    void writesThroughTheResourceLastAdaptedAtThePath() throws PersistenceException
    {
        final PropertyTagging tagging = PropertyTagging.installIn(this.context);
        final Taggable taggable = tagging.viewOf(unmodifiable());

        taggable(DRAFT);

        assertTrue(taggable.tag(SENSITIVE));
        assertEquals(List.of(DRAFT, SENSITIVE), stored());
    }

    @Test
    void givesOneViewPerPath()
    {
        final PropertyTagging tagging = PropertyTagging.installIn(this.context);
        final Taggable first = taggable();

        final Resource again = this.context.resourceResolver().getResource(PATH);

        assertSame(first, again.adaptTo(Taggable.class));
        assertSame(first, tagging.viewOf(again));
    }

    @Test
    void answersEmptyForWhatItLeavesOut()
    {
        PropertyTagging.installIn(this.context);
        final Taggable taggable = taggable(DRAFT);

        assertTrue(taggable.getEffectiveTags().isEmpty());
        assertTrue(taggable.getEffectiveTagNames().isEmpty());
        assertTrue(taggable.getApplicableDefinitions().isEmpty());
    }

    private Taggable taggable(final String... tags)
    {
        return this.context.create().resource(PATH, Map.of(TAGS, tags)).adaptTo(Taggable.class);
    }

    /** A resource at the test path that adapts to nothing, as one the session may not modify. */
    private static Resource unmodifiable()
    {
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.getPath()).thenReturn(PATH);
        Mockito.when(resource.getValueMap()).thenReturn(ValueMap.EMPTY);
        return resource;
    }

    private List<String> stored()
    {
        return List.of(this.context.resourceResolver().getResource(PATH).getValueMap().get(TAGS, new String[0]));
    }
}
