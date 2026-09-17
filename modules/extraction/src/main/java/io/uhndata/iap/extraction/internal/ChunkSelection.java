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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.uhndata.iap.submissions.models.Chunk;

/**
 * Which chunks are worth showing a model, for a given set of fields.
 *
 * <p>Selection is tag-driven: the fields say which parts of a proposal could hold their answers, the chunks say
 * which part they are, and a chunk is worth sending when those overlap.
 *
 * <p>It errs towards sending. A chunk is only left out when it has been read and confidently placed somewhere
 * the fields do not ask about — an untagged chunk, one tagged from headings alone, or one whose tagging was
 * marked uncertain is still sent, because the alternative is to decide a chunk cannot hold an answer on the
 * strength of a guess. A field that names no tags asks about the whole document, so it selects everything.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ChunkSelection
{
    private ChunkSelection()
    {
        // Utility
    }

    /**
     * The chunks that could hold an answer to any of the given fields, in document order.
     *
     * @param fields what is being looked for
     * @param chunks the document's chunks
     * @return the chunks worth showing, which is all of them when nothing can be ruled out
     */
    static List<Chunk> select(final List<ExtractionField> fields, final List<Chunk> chunks)
    {
        return select(wantedTags(fields), chunks);
    }

    /**
     * Every part of a proposal any of the fields could be answered from.
     *
     * @param fields what is being looked for
     * @return the union of their tags; empty when a field asks about the document as a whole, which makes the
     *         tag join pointless
     */
    static Set<String> wantedTags(final List<ExtractionField> fields)
    {
        return asksAboutEverything(fields) ? Set.of() : union(fields);
    }

    /**
     * The chunks that could hold any of the given parts of a proposal, in document order.
     *
     * @param wanted the rubric tags being looked for; empty to look everywhere
     * @param chunks the document's chunks
     * @return the chunks worth showing, which is all of them when nothing can be ruled out
     */
    static List<Chunk> select(final Set<String> wanted, final List<Chunk> chunks)
    {
        if (wanted.isEmpty()) {
            return List.copyOf(chunks);
        }
        final List<Chunk> selected = new ArrayList<>(chunks.size());
        for (final Chunk chunk : chunks) {
            if (couldHoldAnAnswer(chunk, wanted)) {
                selected.add(chunk);
            }
        }
        return selected;
    }

    /**
     * Whether any field asks about the document as a whole, which makes the tag join pointless.
     *
     * @param fields the fields being looked for
     * @return {@code true} when at least one field names no tags
     */
    private static boolean asksAboutEverything(final List<ExtractionField> fields)
    {
        return fields.isEmpty() || fields.stream().anyMatch(field -> field.rubricTags().isEmpty());
    }

    /**
     * Every part of a proposal any of the fields could be answered from.
     *
     * @param fields the fields being looked for
     * @return the union of their tags
     */
    private static Set<String> union(final List<ExtractionField> fields)
    {
        final Set<String> wanted = new HashSet<>();
        for (final ExtractionField field : fields) {
            wanted.addAll(field.rubricTags());
        }
        return wanted;
    }

    /**
     * Whether a chunk is worth showing: either it is tagged as one of the parts being asked about, or its
     * tagging is not solid enough to rule it out.
     *
     * @param chunk the chunk to weigh up
     * @param wanted the parts of a proposal being asked about
     * @return {@code true} when the chunk should be sent
     */
    private static boolean couldHoldAnAnswer(final Chunk chunk, final Set<String> wanted)
    {
        final List<String> tags = chunk.getRubricTags();
        // A chunk nothing has placed is a wildcard: it could hold anything, so it matches every field and
        // stays eligible for every later pass. Ruling one out on a tag nobody assigned is how this silently
        // goes fail-closed, with a field reported absent from a chunk that was never read.
        return tags.isEmpty() || tags.stream().anyMatch(wanted::contains);
    }
}
