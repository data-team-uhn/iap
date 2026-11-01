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

import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;

import io.uhndata.iap.search.api.Operator;

/**
 * Compares whole numbers, dates and numbers as such, over the points {@link PropertyFields} indexes them as.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class NumberComparisons
{
    private NumberComparisons()
    {
        // Utility class
    }

    /**
     * Matches the whole numbers, or the dates as milliseconds, a comparison holds for.
     *
     * @param field a field holding the values as points
     * @param operator one of {@code =}, {@code <}, {@code <=}, {@code >} or {@code >=}
     * @param number the whole number compared against
     * @return the query
     */
    static Query compare(final String field, final Operator operator, final long number)
    {
        return switch (operator) {
            case LT -> number == Long.MIN_VALUE ? new MatchNoDocsQuery()
                : LongPoint.newRangeQuery(field, Long.MIN_VALUE, number - 1);
            case LTE -> LongPoint.newRangeQuery(field, Long.MIN_VALUE, number);
            case GT -> number == Long.MAX_VALUE ? new MatchNoDocsQuery()
                : LongPoint.newRangeQuery(field, number + 1, Long.MAX_VALUE);
            case GTE -> LongPoint.newRangeQuery(field, number, Long.MAX_VALUE);
            default -> LongPoint.newExactQuery(field, number);
        };
    }

    /**
     * Matches the numbers a comparison holds for.
     *
     * @param field a field holding the values as points
     * @param operator one of {@code =}, {@code <}, {@code <=}, {@code >} or {@code >=}
     * @param number the number compared against
     * @return the query
     */
    static Query compare(final String field, final Operator operator, final double number)
    {
        return switch (operator) {
            case LT -> DoublePoint.newRangeQuery(field, Double.NEGATIVE_INFINITY, DoublePoint.nextDown(number));
            case LTE -> DoublePoint.newRangeQuery(field, Double.NEGATIVE_INFINITY, number);
            case GT -> DoublePoint.newRangeQuery(field, DoublePoint.nextUp(number), Double.POSITIVE_INFINITY);
            case GTE -> DoublePoint.newRangeQuery(field, number, Double.POSITIVE_INFINITY);
            default -> DoublePoint.newExactQuery(field, number);
        };
    }
}
