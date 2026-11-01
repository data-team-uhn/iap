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
package io.uhndata.iap.search.index.internal;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;

import io.uhndata.iap.search.api.Operator;

/**
 * Compares the text of values, as a JCR query does when the value compared against is text or does not convert to
 * the stored type. The order is that of the text's code points, which is Java's except between characters beyond
 * the basic plane and the private use and specials area just below its end, which no listing compares by.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TextComparisons
{
    private TextComparisons()
    {
        // Utility class
    }

    /**
     * Matches the values a comparison holds for.
     *
     * @param field a field holding the text of values
     * @param operator one of {@code =}, {@code <}, {@code <=}, {@code >} or {@code >=}
     * @param text the text compared against
     * @return the query
     */
    static Query compare(final String field, final Operator operator, final String text)
    {
        return switch (operator) {
            case LT -> TermRangeQuery.newStringRange(field, null, text, false, false);
            case LTE -> TermRangeQuery.newStringRange(field, null, text, false, true);
            case GT -> TermRangeQuery.newStringRange(field, text, null, false, false);
            case GTE -> TermRangeQuery.newStringRange(field, text, null, true, false);
            default -> new TermQuery(new Term(field, text));
        };
    }
}
