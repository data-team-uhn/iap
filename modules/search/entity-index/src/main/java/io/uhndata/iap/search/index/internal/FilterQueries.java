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

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

import io.uhndata.iap.search.api.Filter;
import io.uhndata.iap.search.api.Operator;

/**
 * Turns a listing's conditions into queries matching the documents a JCR query's conditions would. A JCR query
 * converts the value compared against to the type of each stored value, and compares the two as text when it does
 * not convert: {@code = '3.0'} misses a stored whole number 3 but finds a stored number 3.0, and {@code LIKE '1%'}
 * finds the whole numbers 10 and 12 by their text. So each condition is asked of every type the property is stored
 * in, the value converted by the repository's own rules.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class FilterQueries
{
    private final StoredTypes stored;

    private final ValueFactory values;

    /**
     * Turns conditions into queries over an index.
     *
     * @param stored the types each property is stored in, in the index
     * @param values converts a value compared against to a stored type, as the repository does
     */
    FilterQueries(final StoredTypes stored, final ValueFactory values)
    {
        this.stored = stored;
        this.values = values;
    }

    /**
     * The documents a condition holds for.
     *
     * @param filter the condition
     * @return the query
     * @throws RepositoryException if a converted value cannot be read, which a value the repository just made can
     */
    Query of(final Filter filter) throws RepositoryException
    {
        final String property = filter.getName();
        final String value = Objects.toString(filter.getValue(), "");
        return switch (filter.getComparator()) {
            case IS_NOT_NULL -> isSet(property);
            case IS_NULL -> not(isSet(property));
            // Matching whatever has no value equal to it, which is what `not x = y` means to the repository
            case NEQ -> not(compare(property, Operator.EQ, value));
            case NOT_LIKE -> not(compare(property, Operator.LIKE, value));
            case ILIKE -> caseInsensitiveLike(property, value);
            case NOT_ILIKE -> not(caseInsensitiveLike(property, value));
            default -> compare(property, filter.getComparator(), value);
        };
    }

    private Query compare(final String property, final Operator operator, final String value)
        throws RepositoryException
    {
        final BooleanQuery.Builder anyType = new BooleanQuery.Builder();
        for (final int type : this.stored.of(property)) {
            anyType.add(compare(IndexFields.typed(property, type), type, operator, value), Occur.SHOULD);
        }
        return anyType.build();
    }

    private Query compare(final String field, final int type, final Operator operator, final String value)
        throws RepositoryException
    {
        final Optional<Value> converted = convert(value, type);
        final String text = converted.isPresent() ? converted.get().getString() : value;
        if (operator == Operator.LIKE) {
            // Matched as text either way, against the converted value's text when it converts
            return LikePatterns.query(field, text);
        }
        if (converted.isPresent() && IndexFields.isNumber(type)) {
            return compareNumbers(IndexFields.number(field), type, operator, converted.get());
        }
        return TextComparisons.compare(field, operator, text);
    }

    private static Query compareNumbers(final String field, final int type, final Operator operator,
        final Value number) throws RepositoryException
    {
        return switch (type) {
            case PropertyType.DOUBLE -> NumberComparisons.compare(field, operator, number.getDouble());
            case PropertyType.DATE -> NumberComparisons.compare(field, operator, number.getDate().getTimeInMillis());
            default -> NumberComparisons.compare(field, operator, number.getLong());
        };
    }

    private Optional<Value> convert(final String value, final int type)
    {
        try {
            return Optional.of(this.values.createValue(value, type));
        } catch (final RepositoryException doesNotConvert) {
            return Optional.empty();
        }
    }

    private static Query caseInsensitiveLike(final String property, final String value)
    {
        return LikePatterns.query(IndexFields.lowercase(property), value.toLowerCase(Locale.ROOT));
    }

    private static Query isSet(final String property)
    {
        return new TermQuery(new Term(IndexFields.PROPERTIES_FIELD, property));
    }

    private static Query not(final Query query)
    {
        return new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), Occur.FILTER)
            .add(query, Occur.MUST_NOT)
            .build();
    }
}
