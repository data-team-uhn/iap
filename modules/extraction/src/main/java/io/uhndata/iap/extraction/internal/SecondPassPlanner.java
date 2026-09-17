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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which fields still need asking, and which chunks to ask them over.
 *
 * <p>Pure planning. No calls, no repository. It needs the fields' tags, the chunks' tags, and what the call
 * record says has been read.
 *
 * <p>The targeted pass groups fields by their tags and gives each group its own call. Say sampleSize and
 * statsMethod are both B.5, and consentProcess is B.11. That is two calls: one over the B.5 chunks, one over
 * the B.11 chunks. One call for all three would send the B.11 chunks to the B.5 fields and waste the budget.
 *
 * <p>The sweep then takes whatever is still unanswered over whatever is still unread.
 *
 * <p>Coverage is per field. If sampleSize has read Chunk-1 and statsMethod has not, Chunk-1 still goes to
 * statsMethod.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SecondPassPlanner
{
    private SecondPassPlanner()
    {
        // Utility
    }

    /**
     * One call to make: some fields, and the chunks to ask them over.
     *
     * @param fields what to ask
     * @param chunkIds what to read, in document order
     * @version $Id$
     * @since 0.1.0
     */
    record Batch(List<ExtractionField> fields, Set<String> chunkIds)
    {
        /**
         * Takes copies, so a plan cannot be changed after it was made.
         *
         * @param fields what to ask
         * @param chunkIds what to read
         */
        Batch
        {
            fields = List.copyOf(fields);
            chunkIds = Set.copyOf(chunkIds);
        }
    }

    /**
     * What a chunk is about and what it has to say, as the planner needs it.
     *
     * @param id the chunk's name
     * @param tags what it was placed under, empty when nothing has placed it
     * @param tokens roughly how much text it holds
     * @version $Id$
     * @since 0.1.0
     */
    record ChunkFacts(String id, Set<String> tags, int tokens)
    {
        /**
         * Takes a copy of the tags.
         *
         * @param id the chunk's name
         * @param tags what it was placed under
         * @param tokens roughly how much text it holds
         */
        ChunkFacts
        {
            tags = Set.copyOf(tags);
        }
    }

    /**
     * The targeted pass: one batch per group of fields asking about the same parts of a proposal.
     *
     * @param fields the fields still to answer
     * @param chunks every chunk of the document, in document order
     * @param examined what has already been read for each field, by field name
     * @param tokenBudget how much text one call may carry
     * @return the calls to make, in the order to make them, empty when there is nothing left to read
     */
    static List<Batch> planTargeted(final List<ExtractionField> fields, final List<ChunkFacts> chunks,
        final Map<String, Set<String>> examined, final long tokenBudget)
    {
        final List<Batch> batches = new ArrayList<>();
        for (final Map.Entry<Set<String>, List<ExtractionField>> group : groupByTags(fields).entrySet()) {
            final Set<String> candidates = new LinkedHashSet<>();
            for (final ChunkFacts chunk : chunks) {
                if (couldHold(chunk, group.getKey()) && notYetRead(chunk, group.getValue(), examined)) {
                    candidates.add(chunk.id());
                }
            }
            final Set<String> packed = pack(chunks, candidates, tokenBudget);
            if (!packed.isEmpty()) {
                batches.add(new Batch(group.getValue(), packed));
            }
        }
        return batches;
    }

    /**
     * The sweep: everything still unanswered, over everything no call has read for it.
     *
     * <p>No tag filtering. The targeted pass already looked where the tags pointed. If the answer was not
     * there, the tag was probably wrong, so following it again would repeat the mistake.
     *
     * @param fields the fields still to answer
     * @param chunks every chunk of the document, in document order
     * @param examined what has already been read for each field, by field name
     * @param tokenBudget how much text one call may carry
     * @return the call to make, or {@code null} when there is nothing left to read
     */
    static Batch planSweep(final List<ExtractionField> fields, final List<ChunkFacts> chunks,
        final Map<String, Set<String>> examined, final long tokenBudget)
    {
        if (fields.isEmpty()) {
            return null;
        }
        final Set<String> candidates = new LinkedHashSet<>();
        for (final ChunkFacts chunk : chunks) {
            if (notYetRead(chunk, fields, examined)) {
                candidates.add(chunk.id());
            }
        }
        final Set<String> packed = pack(chunks, candidates, tokenBudget);
        return packed.isEmpty() ? null : new Batch(fields, packed);
    }

    /** Fields with the same tags share a call. Sorted, so [B.5, B.10] and [B.10, B.5] group together. */
    private static Map<Set<String>, List<ExtractionField>> groupByTags(final List<ExtractionField> fields)
    {
        final Map<Set<String>, List<ExtractionField>> groups = new LinkedHashMap<>();
        for (final ExtractionField field : fields) {
            groups.computeIfAbsent(new TreeSet<>(field.rubricTags()), key -> new ArrayList<>()).add(field);
        }
        return groups;
    }

    /** Untagged chunks match everything. A group with no tags asks about the whole document. */
    private static boolean couldHold(final ChunkFacts chunk, final Set<String> wanted)
    {
        return wanted.isEmpty() || chunk.tags().isEmpty() || chunk.tags().stream().anyMatch(wanted::contains);
    }

    /** True if any field in the group has not seen this chunk yet. One field is reason enough to send it. */
    private static boolean notYetRead(final ChunkFacts chunk, final List<ExtractionField> fields,
        final Map<String, Set<String>> examined)
    {
        return fields.stream()
            .anyMatch(field -> !examined.getOrDefault(field.name(), Set.of()).contains(chunk.id()));
    }

    /** Candidates in document order until the budget runs out. The first one always goes, however large. */
    private static Set<String> pack(final List<ChunkFacts> chunks, final Set<String> candidates,
        final long tokenBudget)
    {
        final long budget = Math.max(0L, tokenBudget);
        final Set<String> packed = new LinkedHashSet<>();
        long used = 0;
        for (final ChunkFacts chunk : chunks) {
            if (!candidates.contains(chunk.id())) {
                continue;
            }
            if (used > 0 && used + chunk.tokens() > budget) {
                break;
            }
            packed.add(chunk.id());
            used += chunk.tokens();
            if (used >= budget) {
                break;
            }
        }
        return packed;
    }
}
