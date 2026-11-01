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

import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;

/**
 * The fields a property's values are indexed as, under the names {@link IndexFields} gives them. Every value but a
 * binary one is its text, as a whole-value term, a lower-case copy and a sort key; a whole number or a date is also a
 * point and a sort key over its {@code long}, and a number one over its {@code double}.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class PropertyFields
{
    /** Text longer than this is kept only in part, since a term has a size limit. */
    static final int MAX_TEXT_LENGTH = 4096;

    /** How much of a text orders it. */
    private static final int MAX_SORT_KEY_LENGTH = 256;

    private PropertyFields()
    {
        // Utility class
    }

    /**
     * Indexes one property of a node.
     *
     * @param property the property to index
     * @param document the node's document, which the fields are added to
     * @throws RepositoryException if the property cannot be read
     */
    static void add(final Property property, final Document document) throws RepositoryException
    {
        final String name = property.getName();
        document.add(new StringField(IndexFields.PROPERTIES_FIELD, name, Store.NO));
        final int type = property.getType();
        if (type == PropertyType.BINARY) {
            // Its content is not something a listing compares
            return;
        }
        final String field = IndexFields.typed(name, type);
        document.add(new StringField(IndexFields.TYPED_FIELD, field, Store.NO));
        if (property.isMultiple()) {
            document.add(new StringField(IndexFields.MULTIPLE_FIELD, name, Store.NO));
        }
        for (final Value value : property.isMultiple() ? property.getValues() : new Value[] { property.getValue() }) {
            addText(document, name, field, value.getString());
            switch (type) {
                case PropertyType.LONG -> addWholeNumber(document, IndexFields.number(field), value.getLong());
                case PropertyType.DATE ->
                    addWholeNumber(document, IndexFields.number(field), value.getDate().getTimeInMillis());
                case PropertyType.DOUBLE -> addNumber(document, IndexFields.number(field), value.getDouble());
                default -> {
                    // Text is all there is to it
                }
            }
        }
    }

    private static void addWholeNumber(final Document document, final String field, final long number)
    {
        document.add(new LongPoint(field, number));
        document.add(new SortedNumericDocValuesField(field, number));
    }

    private static void addNumber(final Document document, final String field, final double number)
    {
        document.add(new DoublePoint(field, number));
        document.add(new SortedNumericDocValuesField(field, NumericUtils.doubleToSortableLong(number)));
    }

    private static void addText(final Document document, final String property, final String field,
        final String text)
    {
        final String kept = StringUtils.truncate(text, MAX_TEXT_LENGTH);
        if (kept.length() < text.length()) {
            document.add(new StringField(IndexFields.TRUNCATED_FIELD, property, Store.NO));
        }
        document.add(new StringField(field, kept, Store.NO));
        document.add(new StringField(IndexFields.lowercase(property), kept.toLowerCase(Locale.ROOT), Store.NO));
        document.add(new SortedSetDocValuesField(field, new BytesRef(StringUtils.truncate(text, MAX_SORT_KEY_LENGTH))));
    }
}
