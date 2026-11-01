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

import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.search.api.Operator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link NumberComparisons}, at the ends of the range, which the conditions' tests do not reach.
 *
 * @version $Id$
 * @since 0.1.0
 */
class NumberComparisonsTest
{
    private static final String FIELD = "value|Long|number";

    @Test
    void findsNothingBelowTheLeastWholeNumber() throws Exception
    {
        assertEquals(List.of(), found(Operator.LT, Long.MIN_VALUE));
        assertEquals(List.of("/least"), found(Operator.LTE, Long.MIN_VALUE));
    }

    @Test
    void findsNothingAboveTheGreatestWholeNumber() throws Exception
    {
        assertEquals(List.of(), found(Operator.GT, Long.MAX_VALUE));
        assertEquals(List.of("/greatest"), found(Operator.GTE, Long.MAX_VALUE));
    }

    private static List<String> found(final Operator operator, final long number) throws Exception
    {
        try (SearchedIndex index = SearchedIndex.of(List.of(holding("/least", Long.MIN_VALUE),
            holding("/greatest", Long.MAX_VALUE)))) {
            return index.paths(NumberComparisons.compare(FIELD, operator, number));
        }
    }

    private static Document holding(final String path, final long number)
    {
        final Document document = new Document();
        document.add(new StoredField(IndexFields.PATH_FIELD, path));
        document.add(new LongPoint(FIELD, number));
        return document;
    }
}
