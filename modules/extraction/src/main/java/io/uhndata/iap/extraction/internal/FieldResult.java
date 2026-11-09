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
 * What the model answered for one question, after its quotes were checked against the document.
 *
 * @param key the question's key, see {@link ExtractionField#getKey}
 * @param values the answer as text, one entry per value; empty when nothing was found
 * @param reasoning how the model got there, or what it looked for; empty when it gave none
 * @param confidence how sure the model is, from 0 to 1, lowered when its quotes could not be found
 * @param passages the quotes that were found in the document
 * @param quotesOffered how many different quotes the model gave, found or not
 * @version $Id$
 * @since 0.1.0
 */
record FieldResult(String key, List<String> values, String reasoning, double confidence, List<Passage> passages,
    int quotesOffered)
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
    }

    /**
     * Copies the lists, so a result cannot change after it is made.
     *
     * @param key the question's key
     * @param values the answer, empty when nothing was found
     * @param reasoning how the model got there
     * @param confidence how sure the model is
     * @param passages the quotes found in the document
     * @param quotesOffered how many different quotes the model gave
     */
    FieldResult
    {
        values = List.copyOf(values);
        passages = List.copyOf(passages);
    }

    /**
     * The result for a question the reply left out: nothing found.
     *
     * @param key the question's key
     * @return an empty result
     */
    static FieldResult createNotFound(final String key)
    {
        return new FieldResult(key, List.of(), "", 0.0, List.of(), 0);
    }

    /**
     * Whether the model gave an answer.
     *
     * @return {@code true} when there is at least one value
     */
    boolean found()
    {
        return !this.values.isEmpty();
    }
}
