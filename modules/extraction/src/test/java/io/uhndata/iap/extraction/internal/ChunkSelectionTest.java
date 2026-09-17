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
package io.uhndata.iap.extraction.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.models.Chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ChunkSelection}: which chunks are worth showing a model, and the care it takes not to
 * rule one out on the strength of a guess.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ChunkSelectionTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    private int created;

    private static ExtractionField field(final String... tags)
    {
        return new ExtractionField("aims", "What are the aims?", "", "Find the aims.", null,
            List.of(tags), false);
    }

    private Chunk chunk(final Double tagConfidence, final String... tags)
    {
        this.created++;
        final Map<String, Object> properties = new HashMap<>();
        properties.put("sling:resourceType", Chunk.RESOURCE_TYPE);
        if (tagConfidence != null) {
            properties.put("tagConfidence", tagConfidence);
        }
        if (tags.length > 0) {
            properties.put("rubricTags", tags);
        }
        final Resource resource =
            this.context.create().resource("/chunks/Chunk-" + this.created, properties);
        final Chunk model = resource.adaptTo(Chunk.class);
        assertNotNull(model);
        return model;
    }

    private static List<String> namesOf(final List<Chunk> chunks)
    {
        final List<String> names = new ArrayList<>(chunks.size());
        for (final Chunk chunk : chunks) {
            names.add(chunk.getName());
        }
        return names;
    }

    @Test
    void sendsAChunkTaggedAsSomethingTheFieldsAskAbout()
    {
        final Chunk wanted = chunk(0.9, "B.3");
        final Chunk other = chunk(0.9, "B.9");

        final List<Chunk> selected = ChunkSelection.select(List.of(field("B.3", "B.4")),
            List.of(wanted, other));

        assertEquals(List.of("Chunk-1"), namesOf(selected));
    }

    @Test
    void keepsTheDocumentOrder()
    {
        final Chunk first = chunk(0.9, "B.3");
        final Chunk skipped = chunk(0.9, "B.9");
        final Chunk last = chunk(0.9, "B.4");

        final List<Chunk> selected = ChunkSelection.select(List.of(field("B.3", "B.4")),
            List.of(first, skipped, last));

        assertEquals(List.of("Chunk-1", "Chunk-3"), namesOf(selected));
    }

    // The load-bearing fail-open rule. A chunk nothing has placed could hold anything, so it matches every
    // field. Ruling one out on a tag nobody assigned is how this silently goes fail-closed, reporting a field
    // absent from text that was never read.
    @Test
    void sendsAChunkNobodyHasTagged()
    {
        final Chunk untagged = chunk(null);

        assertEquals(1, ChunkSelection.select(List.of(field("B.3")), List.of(untagged)).size());
    }

    @Test
    void sendsAChunkTaggedWithNoConfidenceRecorded()
    {
        final Chunk untagged = chunk(0.9);

        assertEquals(1, ChunkSelection.select(List.of(field("B.3")), List.of(untagged)).size());
    }

    // A placement is a placement whoever made it: the gate reading headings and the intake reading the text
    // both say what the chunk is about, and neither is a reason to send text about something else.
    @Test
    void leavesOutAChunkPlacedSomewhereTheFieldsDoNotAskAbout()
    {
        final Chunk elsewhere = chunk(0.9, "B.9");

        assertEquals(0, ChunkSelection.select(List.of(field("B.3")), List.of(elsewhere)).size());
    }

    // Confidence is advisory. A confidently wrong tag rules a chunk out exactly as firmly as a confidently
    // right one, so selection turns on the tags rather than on how sure anything was.
    @Test
    void leavesOutAChunkPlacedElsewhereEvenWithoutMuchConfidence()
    {
        final Chunk unsure = chunk(0.1, "B.9");

        assertEquals(0, ChunkSelection.select(List.of(field("B.3")), List.of(unsure)).size());
    }

    @Test
    void leavesOutOnlyAChunkAModelReadAndPlacedElsewhere()
    {
        final Chunk placed = chunk(0.9, "B.9");

        assertEquals(0, ChunkSelection.select(List.of(field("B.3")), List.of(placed)).size());
    }

    @Test
    void sendsEverythingForAFieldThatNamesNoTags()
    {
        final Chunk one = chunk(0.9, "B.9");
        final Chunk two = chunk(0.9, "B.8");

        assertEquals(2, ChunkSelection.select(List.of(field("B.3"), field()), List.of(one, two)).size(),
            "a field that names no tags asks about the whole document");
    }

    @Test
    void sendsEverythingWhenThereIsNothingToLookFor()
    {
        final Chunk one = chunk(0.9, "B.9");

        assertEquals(1, ChunkSelection.select(List.of(), List.of(one)).size());
    }

    @Test
    void selectsByTagsDirectly()
    {
        final Chunk one = chunk(0.9, "B.9");
        final Chunk two = chunk(0.9, "B.3");

        assertEquals(List.of(two.getName()), namesOf(ChunkSelection.select(Set.of("B.3"), List.of(one, two))));
    }

    @Test
    void looksEverywhereWhenNoTagsAreWanted()
    {
        final Chunk one = chunk(0.9, "B.9");

        assertEquals(1, ChunkSelection.select(Set.of(), List.of(one)).size());
    }

    @Test
    void wantsEveryTagTheFieldsName()
    {
        assertEquals(Set.of("B.3", "B.4"), ChunkSelection.wantedTags(List.of(field("B.3"), field("B.3", "B.4"))));
    }

    @Test
    void wantsNothingInParticularWhenAFieldNamesNoTags()
    {
        assertTrue(ChunkSelection.wantedTags(List.of(field("B.3"), field())).isEmpty(),
            "that field asks about the whole document");
        assertTrue(ChunkSelection.wantedTags(List.of()).isEmpty());
    }
}
