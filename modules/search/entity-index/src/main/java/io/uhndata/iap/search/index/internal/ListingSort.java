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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.jcr.PropertyType;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.SortedSetSortField;
import org.apache.lucene.search.TermQuery;

/**
 * The order a listing asks for, when the index can give the one a JCR query would. The repository puts entities
 * without the property first, then orders values of different types by type before value, and a property with
 * several values by its first one. The index orders one type, and the least of several values, so a property
 * stored in more than one type, or with several values, is left to the repository.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ListingSort
{
    private ListingSort()
    {
        // Utility class
    }

    /**
     * The order of the entities, by one of their properties.
     *
     * @param property the property to order by
     * @param descending whether the order is reversed
     * @param searcher reads the index
     * @param stored the types each property is stored in
     * @return the order, or empty if the index cannot give the one the repository would
     * @throws IOException if the index cannot be read
     */
    static Optional<Sort> of(final String property, final boolean descending, final IndexSearcher searcher,
        final StoredTypes stored) throws IOException
    {
        if (countEntities(searcher, IndexFields.MULTIPLE_FIELD, property) > 0) {
            return Optional.empty();
        }
        final List<Integer> types = new ArrayList<>();
        for (final int type : stored.of(property)) {
            if (countEntities(searcher, IndexFields.TYPED_FIELD, IndexFields.typed(property, type)) > 0) {
                types.add(type);
            }
        }
        if (types.isEmpty()) {
            // No entity has the property, so they all tie
            return Optional.of(Sort.INDEXORDER);
        }
        if (types.size() > 1) {
            return Optional.empty();
        }
        return Optional.of(new Sort(field(IndexFields.typed(property, types.get(0)), types.get(0), descending)));
    }

    private static SortField field(final String typedField, final int type, final boolean descending)
    {
        // Missing sorts as the least value, first ascending and last descending, as in the repository
        final SortField field;
        if (type == PropertyType.DOUBLE) {
            field = new SortedNumericSortField(IndexFields.number(typedField), SortField.Type.DOUBLE, descending);
            field.setMissingValue(Double.NEGATIVE_INFINITY);
        } else if (IndexFields.isNumber(type)) {
            field = new SortedNumericSortField(IndexFields.number(typedField), SortField.Type.LONG, descending);
            field.setMissingValue(Long.MIN_VALUE);
        } else {
            field = new SortedSetSortField(typedField, descending);
            field.setMissingValue(SortField.STRING_FIRST);
        }
        return field;
    }

    private static int countEntities(final IndexSearcher searcher, final String field, final String value)
        throws IOException
    {
        return searcher.count(new BooleanQuery.Builder()
            .add(new TermQuery(new Term(IndexFields.KIND_FIELD, IndexFields.ENTITY_KIND)), Occur.FILTER)
            .add(new TermQuery(new Term(field, value)), Occur.FILTER)
            .build());
    }
}
