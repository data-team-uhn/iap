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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;

/**
 * The JCR types each property is stored in somewhere in the index, which says what a condition on it must compare
 * against. A type may linger after the last value of it is gone, which only costs a comparison that finds nothing.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class StoredTypes
{
    private final Map<String, Set<Integer>> types;

    private StoredTypes(final Map<String, Set<Integer>> types)
    {
        this.types = types;
    }

    /**
     * Reads the types off an index.
     *
     * @param reader the index
     * @return the types each property is stored in
     * @throws IOException if the index cannot be read
     */
    static StoredTypes of(final IndexReader reader) throws IOException
    {
        final Map<String, Set<Integer>> types = new HashMap<>();
        final Terms typed = MultiTerms.getTerms(reader, IndexFields.TYPED_FIELD);
        if (typed != null) {
            final TermsEnum fields = typed.iterator();
            for (BytesRef field = fields.next(); field != null; field = fields.next()) {
                final String name = field.utf8ToString();
                types.computeIfAbsent(IndexFields.propertyOf(name), property -> new TreeSet<>())
                    .add(IndexFields.typeOf(name));
            }
        }
        return new StoredTypes(types);
    }

    /**
     * The types a property is stored in.
     *
     * @param property a property name
     * @return the {@link javax.jcr.PropertyType} constants, in their order; none if no node has the property
     */
    Set<Integer> of(final String property)
    {
        return this.types.getOrDefault(property, Set.of());
    }
}
