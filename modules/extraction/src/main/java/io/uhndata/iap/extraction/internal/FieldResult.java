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
 * What the model answered for one field, after its quotes were checked against the document.
 *
 * @param name the field's key, the question's path within its schema version
 * @param found whether the model found an answer and gave a value for it
 * @param confidence how sure the model is, from 0 to 1, lowered when its quotes could not be found
 * @param value the answer, or {@code null} when nothing was found
 * @param reasoning why the model gave this answer, empty when it gave no reason
 * @param passages the quotes that were found in the document
 * @version $Id$
 * @since 0.1.0
 */
record FieldResult(String name, boolean found, double confidence, String value, String reasoning,
    List<Passage> passages)
{
    /**
     * One quote from the document, and where it is.
     *
     * @param quote the quoted words, as the model gave them
     * @param page the page the model gave, not checked; {@code null} when it gave none
     * @param header the nearest heading above the quote, empty when there is none
     * @param part the index of the document it was found in, when several were sent together; -1 when the
     *     document was sent alone, without a heading
     * @version $Id$
     * @since 0.1.0
     */
    record Passage(String quote, Long page, String header, int part)
    {
        /**
         * A quote as the model gave it, before it is found in the document: no heading, no document index.
         *
         * @param quote the quoted words
         * @param page the page the model gave, or {@code null}
         */
        Passage(final String quote, final Long page)
        {
            this(quote, page, "", -1);
        }

        /**
         * A copy of this quote with the heading it was found under.
         *
         * @param heading the nearest heading above it, empty when there is none
         * @return the new passage
         */
        Passage placeUnder(final String heading)
        {
            return new Passage(quote(), page(), heading, part());
        }

        /**
         * A copy of this quote with the index of the document it was found in.
         *
         * @param found the index of the document among those sent together; -1 when the document was sent alone,
         *     without a heading
         * @return the new passage
         */
        Passage placeIn(final int found)
        {
            return new Passage(quote(), page(), header(), found);
        }
    }

    /**
     * Copies the passages, so a result cannot change after it is made.
     *
     * @param name the field's key
     * @param found whether an answer was found
     * @param confidence how sure the model is
     * @param value the answer, or {@code null}
     * @param reasoning why the model gave this answer
     * @param passages the quotes found in the document
     */
    FieldResult
    {
        passages = List.copyOf(passages);
    }
}
