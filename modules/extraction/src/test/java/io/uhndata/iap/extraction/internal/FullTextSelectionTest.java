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

/**
 * Unit tests for {@link FullTextSelection}: which chunks go to the model in full, within a budget.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FullTextSelectionTest
{
    private static final String CHUNK_1 = "Chunk-1";

    private static final String CHUNK_2 = "Chunk-2";

    private static final String CHUNK_3 = "Chunk-3";

    private static final String AIMS_TAG = "B.3";

    private static final String OTHER_TAG = "B.9";

    private static final String AIMS_HEADING = "Aims";

    private static final Set<String> AIMS = Set.of(AIMS_TAG);

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    private int created;

    /** A chunk a model read and placed, so selection can rule it out. */
    private ChunkCatalog.Entry placed(final String text, final String heading, final String... tags)
    {
        this.created++;
        final Map<String, Object> properties = new HashMap<>();
        properties.put("sling:resourceType", Chunk.RESOURCE_TYPE);
        properties.put("tagBasis", "fulltext");
        if (tags.length > 0) {
            properties.put("rubricTags", tags);
        }
        final Resource resource = this.context.create().resource("/chunks/Chunk-" + this.created, properties);
        final Chunk chunk = resource.adaptTo(Chunk.class);
        assertNotNull(chunk);
        return new ChunkCatalog.Entry(chunk, text, List.of(heading));
    }

    private static String text(final int tokens)
    {
        return "x".repeat(tokens * ChunkContent.CHARS_PER_TOKEN);
    }

    @Test
    void sendsEverythingThatFits()
    {
        final List<ChunkCatalog.Entry> entries =
            List.of(placed(text(10), AIMS_HEADING, AIMS_TAG), placed(text(10), "Budget", OTHER_TAG));

        assertEquals(Set.of(CHUNK_1, CHUNK_2), FullTextSelection.select(entries, AIMS, 100),
            "a chunk placed elsewhere still goes when there is room");
    }

    @Test
    void sendsOnlyTheChunksThatCouldHoldTheAnswerWhenNotEverythingFits()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed(text(40), AIMS_HEADING, AIMS_TAG),
            placed(text(40), "Budget", OTHER_TAG),
            placed(text(40), "Consent", AIMS_TAG));

        assertEquals(List.of(CHUNK_1, CHUNK_3), List.copyOf(FullTextSelection.select(entries, AIMS, 100)),
            "in document order");
    }

    @Test
    void looksEverywhereWhenNoTagsAreWanted()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed(text(40), AIMS_HEADING, AIMS_TAG),
            placed(text(40), "Budget", OTHER_TAG),
            placed(text(40), "Consent", AIMS_TAG));

        assertEquals(Set.of(CHUNK_1, CHUNK_2), FullTextSelection.select(entries, Set.of(), 100),
            "the first two fill the budget");
    }

    @Test
    void alwaysSendsTheFirstCandidateHoweverLarge()
    {
        final List<ChunkCatalog.Entry> entries =
            List.of(placed(text(40), AIMS_HEADING, AIMS_TAG), placed(text(40), "Budget", OTHER_TAG));

        assertEquals(Set.of(CHUNK_1), FullTextSelection.select(entries, AIMS, 10));
        assertEquals(Set.of(CHUNK_1), FullTextSelection.select(entries, AIMS, -5), "even with no budget at all");
    }

    @Test
    void sendsTheOpeningWhenEveryChunkWasPlacedElsewhere()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed(text(40), "Budget", OTHER_TAG),
            placed(text(40), "Costs", OTHER_TAG),
            placed(text(40), "Consent", OTHER_TAG));

        assertEquals(Set.of(CHUNK_1), FullTextSelection.select(entries, AIMS, 50),
            "nothing to send would be worse than the opening");
    }

    @Test
    void neverSendsAReferenceList()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed(text(10), AIMS_HEADING, AIMS_TAG),
            placed(text(10), "Methods", AIMS_TAG),
            placed(text(10), "References", "B.17"));

        assertEquals(Set.of(CHUNK_1, CHUNK_2), FullTextSelection.select(entries, AIMS, 1000),
            "however much room there is");
    }

    @Test
    void keepsAReferencesHeadingThatComesEarly()
    {
        final List<ChunkCatalog.Entry> entries = List.of(
            placed(text(10), "References to prior work", AIMS_TAG),
            placed(text(10), AIMS_HEADING, AIMS_TAG),
            placed(text(10), "Methods", AIMS_TAG));

        assertEquals(Set.of(CHUNK_1, CHUNK_2, CHUNK_3), FullTextSelection.select(entries, AIMS, 1000),
            "a reference list is at the end of a document, not its start");
    }
}
