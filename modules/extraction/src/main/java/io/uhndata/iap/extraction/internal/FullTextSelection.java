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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.uhndata.iap.submissions.models.Chunk;

/**
 * Which chunks of a document a model gets to read in full, within a token budget. The whole document when it
 * fits; otherwise the chunks that could hold what is being looked for, packed in document order until the
 * budget runs out. Reference lists are never sent.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class FullTextSelection
{
    /** Where a reference list can be: the last 40% of a document. */
    private static final double REFERENCE_TAIL_FRACTION = 0.6;

    private static final Pattern REFERENCE_HEADING =
        Pattern.compile("(?i)\\b(references|bibliography|works cited|literature cited)\\b");

    private FullTextSelection()
    {
        // Utility
    }

    /**
     * The chunks to send in full.
     *
     * @param entries the document's chunks, read, in document order
     * @param wantedTags the rubric tags being looked for; empty to look everywhere
     * @param tokenBudget how much text may be sent in full
     * @return the names of the chunks to send, in document order
     */
    static Set<String> select(final List<ChunkCatalog.Entry> entries, final Set<String> wantedTags,
        final long tokenBudget)
    {
        final long budget = Math.max(0L, tokenBudget);
        final List<ChunkCatalog.Entry> readable = withoutReferences(entries);
        long all = 0;
        for (final ChunkCatalog.Entry entry : readable) {
            all += ChunkContent.estimateTokens(entry.text());
        }
        if (all <= budget) {
            return names(readable);
        }
        final List<Chunk> chunks = new ArrayList<>(readable.size());
        for (final ChunkCatalog.Entry entry : readable) {
            chunks.add(entry.chunk());
        }
        final Set<String> candidates = new LinkedHashSet<>();
        for (final Chunk chunk : ChunkSelection.select(wantedTags, chunks)) {
            candidates.add(chunk.getName());
        }
        // Every chunk placed elsewhere leaves nothing to send, and nothing is worse than the opening
        return packUntilBudget(readable, candidates.isEmpty() ? names(readable) : candidates, budget);
    }

    /**
     * The candidates in document order, taken until the budget is spent. The first is always taken, however
     * large: sending nothing would be worse than sending one chunk that does not quite fit.
     */
    private static Set<String> packUntilBudget(final List<ChunkCatalog.Entry> entries,
        final Set<String> candidates, final long budget)
    {
        final Set<String> sent = new LinkedHashSet<>();
        long used = 0;
        for (final ChunkCatalog.Entry entry : entries) {
            if (!candidates.contains(entry.getName())) {
                continue;
            }
            final int tokens = ChunkContent.estimateTokens(entry.text());
            if (used > 0 && used + tokens > budget) {
                break;
            }
            sent.add(entry.getName());
            used += tokens;
            if (used >= budget) {
                break;
            }
        }
        return sent;
    }

    private static List<ChunkCatalog.Entry> withoutReferences(final List<ChunkCatalog.Entry> entries)
    {
        final List<ChunkCatalog.Entry> kept = new ArrayList<>(entries.size());
        final int tailStart = (int) (entries.size() * REFERENCE_TAIL_FRACTION);
        for (int index = 0; index < entries.size(); index++) {
            final ChunkCatalog.Entry entry = entries.get(index);
            final boolean references =
                index >= tailStart && REFERENCE_HEADING.matcher(entry.describeHeadings()).find();
            if (!references) {
                kept.add(entry);
            }
        }
        return kept;
    }

    private static Set<String> names(final List<ChunkCatalog.Entry> entries)
    {
        final Set<String> names = new LinkedHashSet<>();
        for (final ChunkCatalog.Entry entry : entries) {
            names.add(entry.getName());
        }
        return names;
    }
}
