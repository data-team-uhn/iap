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

/**
 * What a model read out of a document for one field.
 *
 * @param name the field, which is the question's node name
 * @param found whether the model found an answer; when {@code false}, {@link #value} is {@code null}
 * @param confidence how sure the model was, from 0 to 1, after any discount for quotes that did not check out
 * @param value the answer, or {@code null}
 * @param reasoning why, in the model's words
 * @param passages the quotes backing the answer, each tied to the chunk it was found in. A quote that could
 *            not be found is not here: it is not evidence, so it is dropped rather than stored
 * @param needsSecondLook whether a later pass should ask again: nothing was found, the answer is not sure
 *            enough, or a quote behind it could not be found in the text the model was shown
 * @version $Id$
 * @since 0.1.0
 */
record FieldResult(String name, boolean found, double confidence, String value, String reasoning,
    List<Passage> passages, boolean needsSecondLook)
{
    /**
     * One quote backing an answer, and whether it holds up.
     *
     * @param quote the text, as the model copied it
     * @param chunkId the chunk it came from
     * @param page the page it is on, or {@code null} when the document carries no page markers
     * @param header the nearest heading above the quote, empty when nothing titles it
     * @version $Id$
     * @since 0.1.0
     */
    record Passage(String quote, String chunkId, Long page, String header)
    {
        /**
         * A passage as the model gave it, before anything has found where it really is.
         *
         * @param quote what the model quoted
         * @param chunkId the chunk it says the quote came from
         * @param page the page it says the quote is on, or {@code null} for a source with no page markers
         */
        Passage(final String quote, final String chunkId, final Long page)
        {
            this(quote, chunkId, page, "");
        }

        /**
         * The same passage, placed where the quote was actually found.
         *
         * @param chunk the chunk it was found in, which may not be the one the model named
         * @param heading the nearest heading above it
         * @return a placed passage
         */
        Passage foundIn(final String chunk, final String heading)
        {
            return new Passage(quote(), chunk, page(), heading);
        }
    }

    /**
     * Takes a copy of the passages, so a result cannot be changed once produced.
     *
     * @param name the field
     * @param found whether an answer was found
     * @param confidence how sure the model was
     * @param value the answer
     * @param reasoning the model's explanation
     * @param passages the quotes backing it
     * @param needsSecondLook whether a later pass should ask again
     */
    FieldResult
    {
        passages = List.copyOf(passages);
    }

    /**
     * A result before anything has decided whether it needs another look.
     *
     * @param name the field
     * @param found whether the model said it found an answer, and gave one
     * @param confidence how sure it was, from 0 to 1
     * @param value the answer, or {@code null} when none was found
     * @param reasoning why, in the model's words
     * @param passages the quotes backing it
     */
    FieldResult(final String name, final boolean found, final double confidence, final String value,
        final String reasoning, final List<Passage> passages)
    {
        this(name, found, confidence, value, reasoning, passages, false);
    }
}
