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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.models.Chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    private Chunk chunk(final String tagBasis, final boolean uncertain, final String... tags)
    {
        this.created++;
        final Map<String, Object> properties = new HashMap<>();
        properties.put("sling:resourceType", Chunk.RESOURCE_TYPE);
        properties.put("uncertain", uncertain);
        if (tagBasis != null) {
            properties.put("tagBasis", tagBasis);
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
        final Chunk wanted = chunk("fulltext", false, "B.3");
        final Chunk other = chunk("fulltext", false, "B.9");

        final List<Chunk> selected = ChunkSelection.select(List.of(field("B.3", "B.4")),
            List.of(wanted, other));

        assertEquals(List.of("Chunk-1"), namesOf(selected));
    }

    @Test
    void keepsTheDocumentOrder()
    {
        final Chunk first = chunk("fulltext", false, "B.3");
        final Chunk skipped = chunk("fulltext", false, "B.9");
        final Chunk last = chunk("fulltext", false, "B.4");

        final List<Chunk> selected = ChunkSelection.select(List.of(field("B.3", "B.4")),
            List.of(first, skipped, last));

        assertEquals(List.of("Chunk-1", "Chunk-3"), namesOf(selected));
    }

    @Test
    void sendsAChunkNobodyHasTagged()
    {
        final Chunk untagged = chunk("fulltext", false);

        assertEquals(1, ChunkSelection.select(List.of(field("B.3")), List.of(untagged)).size());
    }

    @Test
    void sendsAChunkWhoseTaggingWasMarkedUncertain()
    {
        final Chunk unsure = chunk("fulltext", true, "B.9");

        assertEquals(1, ChunkSelection.select(List.of(field("B.3")), List.of(unsure)).size());
    }

    @Test
    void sendsAChunkTaggedFromItsHeadingAlone()
    {
        final Chunk guessed = chunk("heading", false, "B.9");

        assertEquals(1, ChunkSelection.select(List.of(field("B.3")), List.of(guessed)).size(),
            "a heading is not enough to rule a chunk out");
    }

    @Test
    void sendsAChunkThatWasNeverPlacedAtAll()
    {
        final Chunk unplaced = chunk(null, false, "B.9");

        assertEquals(1, ChunkSelection.select(List.of(field("B.3")), List.of(unplaced)).size());
    }

    @Test
    void leavesOutOnlyAChunkAModelReadAndPlacedElsewhere()
    {
        final Chunk placed = chunk("deep", false, "B.9");

        assertEquals(0, ChunkSelection.select(List.of(field("B.3")), List.of(placed)).size());
    }

    @Test
    void sendsEverythingForAFieldThatNamesNoTags()
    {
        final Chunk one = chunk("deep", false, "B.9");
        final Chunk two = chunk("deep", false, "B.8");

        assertEquals(2, ChunkSelection.select(List.of(field("B.3"), field()), List.of(one, two)).size(),
            "a field that names no tags asks about the whole document");
    }

    @Test
    void sendsEverythingWhenThereIsNothingToLookFor()
    {
        final Chunk one = chunk("deep", false, "B.9");

        assertEquals(1, ChunkSelection.select(List.of(), List.of(one)).size());
    }
}
