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
 * @param modelConfidence how sure the model said it is, from 0 to 1, as it gave it
 * @param confidence the model's confidence lowered for the quotes that could not be found; what people see and
 *     what thresholds compare with
 * @param passages the quotes that were found in the document
 * @param quotesOffered how many different quotes the model gave, found or not
 * @param origin what produced the answer: the model, the prompt, the template and the threshold;
 *     {@link RunOrigin#NO_ORIGIN} when nothing did
 * @version $Id$
 * @since 0.1.0
 */
record FieldResult(String key, List<String> values, String reasoning, double modelConfidence, double confidence,
    List<Passage> passages, int quotesOffered, RunOrigin origin)
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
     * @param modelConfidence how sure the model said it is
     * @param confidence the model's confidence after the quote check
     * @param passages the quotes found in the document
     * @param quotesOffered how many different quotes the model gave
     * @param origin what produced the answer
     */
    FieldResult
    {
        values = List.copyOf(values);
        passages = List.copyOf(passages);
    }

    /**
     * A result no call has produced yet; {@link #withOrigin} says which one did.
     *
     * @param key the question's key
     * @param values the answer, empty when nothing was found
     * @param reasoning how the model got there
     * @param modelConfidence how sure the model said it is
     * @param confidence the model's confidence after the quote check
     * @param passages the quotes found in the document
     * @param quotesOffered how many different quotes the model gave
     */
    FieldResult(final String key, final List<String> values, final String reasoning, final double modelConfidence,
        final double confidence, final List<Passage> passages, final int quotesOffered)
    {
        this(key, values, reasoning, modelConfidence, confidence, passages, quotesOffered, RunOrigin.NO_ORIGIN);
    }

    /**
     * The same result, produced by the given origin.
     *
     * @param newOrigin what produced the answer
     * @return a copy with the origin
     */
    FieldResult withOrigin(final RunOrigin newOrigin)
    {
        return new FieldResult(this.key, this.values, this.reasoning, this.modelConfidence, this.confidence,
            this.passages, this.quotesOffered, newOrigin);
    }

    /**
     * The same result, held to a confidence threshold.
     *
     * @param threshold the threshold the answer was compared with
     * @return a copy whose origin names the threshold
     */
    FieldResult withThreshold(final double threshold)
    {
        return withOrigin(this.origin.withThreshold(threshold));
    }

    /**
     * The result for a question the reply left out: nothing found.
     *
     * @param key the question's key
     * @return an empty result
     */
    static FieldResult createNotFound(final String key)
    {
        return new FieldResult(key, List.of(), "", 0.0, 0.0, List.of(), 0);
    }

    /**
     * Whether the model gave an answer.
     *
     * @return {@code true} when there is at least one value
     */
    boolean isFound()
    {
        return !this.values.isEmpty();
    }
}
