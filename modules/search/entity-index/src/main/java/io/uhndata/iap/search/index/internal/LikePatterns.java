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
import org.apache.lucene.search.WildcardQuery;

/**
 * Turns a JCR-SQL2 {@code LIKE} pattern into a Lucene query matching the same whole values: {@code %} stands for
 * any run of characters, {@code _} for any one, and a backslash takes the character after it as written. A pattern
 * with no wildcard matches its own text, and only that: an empty one matches an empty value, which an empty
 * Lucene wildcard would not.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class LikePatterns
{
    private LikePatterns()
    {
        // Utility class
    }

    /**
     * The values of a field a pattern matches.
     *
     * @param field the field holding the text of values
     * @param pattern a {@code LIKE} pattern, such as {@code 50\%_off%}
     * @return a wildcard query, such as {@code 50%?off*}, or a term query for a pattern without wildcards
     */
    static Query query(final String field, final String pattern)
    {
        final StringBuilder wildcard = new StringBuilder();
        final StringBuilder text = new StringBuilder();
        boolean wild = false;
        boolean escaped = false;
        for (final char character : pattern.toCharArray()) {
            if (escaped) {
                appendLiteral(wildcard, text, character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '%' || character == '_') {
                wildcard.append(character == '%' ? WildcardQuery.WILDCARD_STRING : WildcardQuery.WILDCARD_CHAR);
                wild = true;
            } else {
                appendLiteral(wildcard, text, character);
            }
        }
        if (escaped) {
            // A backslash ending the pattern escapes nothing, and stands for itself
            appendLiteral(wildcard, text, '\\');
        }
        return wild ? new WildcardQuery(new Term(field, wildcard.toString()))
            : new TermQuery(new Term(field, text.toString()));
    }

    private static void appendLiteral(final StringBuilder wildcard, final StringBuilder text, final char character)
    {
        if (character == WildcardQuery.WILDCARD_STRING || character == WildcardQuery.WILDCARD_CHAR
            || character == WildcardQuery.WILDCARD_ESCAPE) {
            wildcard.append(WildcardQuery.WILDCARD_ESCAPE);
        }
        wildcard.append(character);
        text.append(character);
    }
}
