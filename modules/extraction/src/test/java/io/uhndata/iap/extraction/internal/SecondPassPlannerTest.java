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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.extraction.internal.SecondPassPlanner.Batch;
import io.uhndata.iap.extraction.internal.SecondPassPlanner.ChunkFacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SecondPassPlanner}: which fields go round again, and over what.
 *
 * @version $Id$
 * @since 0.1.0
 */
class SecondPassPlannerTest
{
    private static final long BUDGET = 1000L;

    private static final String SAMPLE = "sampleSize";

    private static final String STATS = "statsMethod";

    private static final String CONSENT = "consentProcess";

    /** The rubric the sample-size questions sit under. */
    private static final String PARTICIPANTS = "B.5";

    private static ExtractionField field(final String name, final String... tags)
    {
        return new ExtractionField(name, "?", "", "Find it.", null, List.of(tags), false);
    }

    private static ChunkFacts chunk(final String id, final int tokens, final String... tags)
    {
        return new ChunkFacts(id, Set.of(tags), tokens);
    }

    // The tracker example: two fields asking about the same part share a call, a third gets its own
    @Test
    void givesEachGroupOfFieldsItsOwnCall()
    {
        final List<ExtractionField> fields =
            List.of(field(SAMPLE, PARTICIPANTS), field(STATS, PARTICIPANTS), field(CONSENT, "B.11"));
        final List<ChunkFacts> chunks =
            List.of(chunk("Chunk-14", 100, PARTICIPANTS), chunk("Chunk-30", 100, PARTICIPANTS),
                chunk("Chunk-41", 100, "B.11"));

        final List<Batch> batches = SecondPassPlanner.planTargeted(fields, chunks, Map.of(), BUDGET);

        assertEquals(2, batches.size());
        assertEquals(List.of(SAMPLE, STATS), batches.get(0).fields().stream().map(ExtractionField::name).toList());
        assertEquals(Set.of("Chunk-14", "Chunk-30"), batches.get(0).chunkIds());
        assertEquals(List.of(CONSENT), batches.get(1).fields().stream().map(ExtractionField::name).toList());
        assertEquals(Set.of("Chunk-41"), batches.get(1).chunkIds());
    }

    @Test
    void groupsFieldsThatNamedTheSameTagsInADifferentOrder()
    {
        final List<ExtractionField> fields =
            List.of(field(SAMPLE, PARTICIPANTS, "B.10"), field(STATS, "B.10", PARTICIPANTS));

        final List<Batch> batches = SecondPassPlanner.planTargeted(fields,
            List.of(chunk("Chunk-1", 100, PARTICIPANTS)), Map.of(), BUDGET);

        assertEquals(1, batches.size());
        assertEquals(2, batches.get(0).fields().size());
    }

    // The load-bearing bit. Two fields reading the same chunk is ordinary, and counting one field's reading
    // as the other's would stop the second field ever seeing it.
    @Test
    void countsWhatHasBeenReadOneFieldAtATime()
    {
        final List<ExtractionField> fields = List.of(field(SAMPLE, PARTICIPANTS));
        final List<ChunkFacts> chunks =
            List.of(chunk("Chunk-1", 100, PARTICIPANTS), chunk("Chunk-2", 100, PARTICIPANTS));

        final List<Batch> forSample =
            SecondPassPlanner.planTargeted(fields, chunks, Map.of(SAMPLE, Set.of("Chunk-1")), BUDGET);
        final List<Batch> forStats = SecondPassPlanner.planTargeted(List.of(field(STATS, PARTICIPANTS)), chunks,
            Map.of(SAMPLE, Set.of("Chunk-1")), BUDGET);

        assertEquals(Set.of("Chunk-2"), forSample.get(0).chunkIds(), "this field has already seen Chunk-1");
        assertEquals(Set.of("Chunk-1", "Chunk-2"), forStats.get(0).chunkIds(), "but this one has not");
    }

    @Test
    void asksNothingWhenEveryCandidateHasBeenRead()
    {
        final List<Batch> batches = SecondPassPlanner.planTargeted(List.of(field(SAMPLE, PARTICIPANTS)),
            List.of(chunk("Chunk-1", 100, PARTICIPANTS)), Map.of(SAMPLE, Set.of("Chunk-1")), BUDGET);

        assertTrue(batches.isEmpty());
    }

    // A chunk nothing has placed could hold anything
    @Test
    void sendsAnUnplacedChunkToEveryGroup()
    {
        final List<Batch> batches = SecondPassPlanner.planTargeted(List.of(field(SAMPLE, PARTICIPANTS)),
            List.of(chunk("Chunk-1", 100)), Map.of(), BUDGET);

        assertEquals(Set.of("Chunk-1"), batches.get(0).chunkIds());
    }

    @Test
    void leavesOutAChunkPlacedSomewhereTheGroupDoesNotAskAbout()
    {
        final List<Batch> batches = SecondPassPlanner.planTargeted(List.of(field(SAMPLE, PARTICIPANTS)),
            List.of(chunk("Chunk-1", 100, "B.17")), Map.of(), BUDGET);

        assertTrue(batches.isEmpty());
    }

    @Test
    void asksAFieldThatNamedNoTagsOverEverything()
    {
        final List<Batch> batches = SecondPassPlanner.planTargeted(List.of(field(SAMPLE)),
            List.of(chunk("Chunk-1", 100, "B.17"), chunk("Chunk-2", 100, PARTICIPANTS)), Map.of(), BUDGET);

        assertEquals(Set.of("Chunk-1", "Chunk-2"), batches.get(0).chunkIds());
    }

    @Test
    void stopsPackingAtTheBudget()
    {
        final List<Batch> batches = SecondPassPlanner.planTargeted(List.of(field(SAMPLE, PARTICIPANTS)),
            List.of(chunk("Chunk-1", 600, PARTICIPANTS), chunk("Chunk-2", 600, PARTICIPANTS)),
            Map.of(), 1000L);

        assertEquals(Set.of("Chunk-1"), batches.get(0).chunkIds());
    }

    // Sending nothing would be worse than sending one chunk that does not quite fit
    @Test
    void sendsTheFirstCandidateHoweverLargeItIs()
    {
        final List<Batch> batches = SecondPassPlanner.planTargeted(List.of(field(SAMPLE, PARTICIPANTS)),
            List.of(chunk("Chunk-1", 9000, PARTICIPANTS)), Map.of(), 1000L);

        assertEquals(Set.of("Chunk-1"), batches.get(0).chunkIds());
    }

    // The targeted pass already asked where the tags said to look, so what is left is a tag being wrong
    @Test
    void sweepsWithoutHonouringTheTagsAgain()
    {
        final Batch sweep = SecondPassPlanner.planSweep(List.of(field(SAMPLE, PARTICIPANTS)),
            List.of(chunk("Chunk-6", 100, "B.17"), chunk("Chunk-7", 100, "B.9")), Map.of(), BUDGET);

        assertEquals(Set.of("Chunk-6", "Chunk-7"), sweep.chunkIds());
    }

    @Test
    void sweepsOnlyWhatHasNotBeenReadForTheField()
    {
        final Batch sweep = SecondPassPlanner.planSweep(List.of(field(SAMPLE, PARTICIPANTS)),
            List.of(chunk("Chunk-6", 100), chunk("Chunk-7", 100)), Map.of(SAMPLE, Set.of("Chunk-6")), BUDGET);

        assertEquals(Set.of("Chunk-7"), sweep.chunkIds());
    }

    @Test
    void sweepsNothingWhenEverythingHasBeenRead()
    {
        assertNull(SecondPassPlanner.planSweep(List.of(field(SAMPLE)),
            List.of(chunk("Chunk-6", 100)), Map.of(SAMPLE, Set.of("Chunk-6")), BUDGET));
    }

    @Test
    void sweepsNothingWhenEveryFieldIsAnswered()
    {
        assertNull(SecondPassPlanner.planSweep(List.of(), List.of(chunk("Chunk-6", 100)), Map.of(), BUDGET));
    }

    @Test
    void plansNothingForADocumentWithNoChunks()
    {
        assertTrue(SecondPassPlanner.planTargeted(List.of(field(SAMPLE)), List.of(), Map.of(), BUDGET).isEmpty());
        assertNull(SecondPassPlanner.planSweep(List.of(field(SAMPLE)), List.of(), Map.of(), BUDGET));
    }
}
