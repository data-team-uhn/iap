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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.uhndata.iap.submissions.models.Chunk;

/**
 * The chunks of a document as a model is shown them: each with its text and the headings that name it.
 *
 * <p>Headings are worked out from the text here, at the moment the catalog is built, rather than read from
 * the node. A chunk that opens mid-prose is a continuation of the section before it, so it inherits the last
 * heading of the previous chunk that had one; anything it goes on to hold itself comes after.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ChunkCatalog
{
    /** What separates one heading from the next when a chunk is named by several. */
    static final String HEADING_SEPARATOR = "; ";

    private ChunkCatalog()
    {
        // Utility
    }

    /**
     * One chunk, read.
     *
     * @param chunk the chunk
     * @param text its Markdown
     * @param headings the headings naming it, inherited one first
     * @version $Id$
     * @since 0.1.0
     */
    record Entry(Chunk chunk, String text, List<String> headings)
    {
        /**
         * Takes a copy of the headings.
         *
         * @param chunk the chunk
         * @param text its Markdown
         * @param headings the headings naming it
         */
        Entry
        {
            headings = List.copyOf(headings);
        }

        /**
         * The chunk's node name, which is how the model refers to it.
         *
         * @return the name
         */
        String getName()
        {
            return this.chunk.getName();
        }

        /**
         * The headings as one line.
         *
         * @return the headings joined, empty when there are none
         */
        String describeHeadings()
        {
            return String.join(HEADING_SEPARATOR, this.headings);
        }
    }

    /**
     * Read every chunk, in document order.
     *
     * @param chunks the document's chunks
     * @return one entry per chunk
     * @throws IOException if a chunk cannot be read
     */
    static List<Entry> read(final List<Chunk> chunks) throws IOException
    {
        final List<Entry> entries = new ArrayList<>(chunks.size());
        String lastHeading = null;
        for (final Chunk chunk : chunks) {
            final String text = ChunkContent.readText(chunk);
            final List<String> own = ChunkContent.getHeadings(text);
            final List<String> headings;
            if (ChunkContent.opensWithHeading(text) || lastHeading == null) {
                headings = own;
            } else {
                headings = new ArrayList<>();
                headings.add(lastHeading);
                headings.addAll(own);
            }
            if (!own.isEmpty()) {
                lastHeading = own.get(own.size() - 1);
            }
            entries.add(new Entry(chunk, text, headings));
        }
        return entries;
    }
}
