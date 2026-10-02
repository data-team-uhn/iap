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
 * What the model answered for one field, after its evidence was checked.
 *
 * @param name the field, as the schema names it
 * @param found whether the model found an answer and gave a value for it
 * @param confidence how sure it is, from 0 to 1, discounted for evidence that could not be found
 * @param value the answer, or {@code null} when nothing was found
 * @param reasoning why it says so, empty when it gave none
 * @param passages the quotes that were really in the document
 * @version $Id$
 * @since 0.1.0
 */
record FieldResult(String name, boolean found, double confidence, String value, String reasoning,
    List<Passage> passages)
{
    /**
     * One quote from the document, with where it sits.
     *
     * @param quote the words themselves, as the model gave them
     * @param page the page it is on, or {@code null} when the document says nothing about pages
     * @param header the nearest heading above it, empty when nothing titles it
     * @param part which of the documents sent together it was found in, -1 when only one was sent
     * @version $Id$
     * @since 0.1.0
     */
    record Passage(String quote, Long page, String header, int part)
    {
        /**
         * A quote before it has been placed under a heading.
         *
         * @param quote the words themselves
         * @param page the page it is on, or {@code null}
         */
        Passage(final String quote, final Long page)
        {
            this(quote, page, "", -1);
        }

        /**
         * The same quote, placed under the heading it was found below.
         *
         * @param heading the nearest heading above it
         * @return the placed passage
         */
        Passage under(final String heading)
        {
            return new Passage(quote(), page(), heading, part());
        }

        /**
         * The same quote, placed in the document it was found in.
         *
         * @param found which of the documents sent together holds it, -1 when only one was sent
         * @return the placed passage
         */
        Passage in(final int found)
        {
            return new Passage(quote(), page(), header(), found);
        }
    }

    /**
     * Takes a copy of the passages, so a result cannot be changed once produced.
     *
     * @param name the field
     * @param found whether an answer was found
     * @param confidence how sure the model is
     * @param value the answer
     * @param reasoning why it says so
     * @param passages the quotes it rests on
     */
    FieldResult
    {
        passages = List.copyOf(passages);
    }
}
