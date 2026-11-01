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
import java.util.Set;

import javax.jcr.PropertyType;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link StoredTypes}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class StoredTypesTest
{
    @Test
    void readsEveryTypeAPropertyIsStoredIn() throws Exception
    {
        try (SearchedIndex index = SearchedIndex.of(List.of(typed("value|Long", "title|String"),
            typed("value|String", "jcr:created|Date")))) {
            final StoredTypes stored = StoredTypes.of(index.reader());

            assertEquals(Set.of(PropertyType.STRING, PropertyType.LONG), stored.of("value"));
            assertEquals(Set.of(PropertyType.DATE), stored.of("jcr:created"));
            assertEquals(Set.of(), stored.of("absent"));
        }
    }

    @Test
    void readsNoTypesOffAnIndexWithNoValues() throws Exception
    {
        try (SearchedIndex index = SearchedIndex.of(List.of(new Document()))) {
            assertEquals(Set.of(), StoredTypes.of(index.reader()).of("value"));
        }
    }

    private static Document typed(final String... fields)
    {
        final Document document = new Document();
        for (final String field : fields) {
            document.add(new StringField(IndexFields.TYPED_FIELD, field, Store.NO));
        }
        return document;
    }
}
