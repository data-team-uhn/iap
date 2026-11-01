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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link LikePatterns}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class LikePatternsTest
{
    private static final String FIELD = "title|String";

    @Test
    void turnsTheWildcardsIntoLucenes()
    {
        assertEquals(wildcard("a*b?c"), LikePatterns.query(FIELD, "a%b_c"));
    }

    @Test
    void takesAnEscapedWildcardAsWritten()
    {
        assertEquals(wildcard("50%?off*"), LikePatterns.query(FIELD, "50\\%_off%"));
        assertEquals(term("a_b"), LikePatterns.query(FIELD, "a\\_b"));
    }

    @Test
    void escapesWhatLuceneWouldReadAsAWildcard()
    {
        assertEquals(wildcard("what\\?\\*%*"), LikePatterns.query(FIELD, "what?*\\%%"));
        assertEquals(term("back\\slash"), LikePatterns.query(FIELD, "back\\\\slash"));
    }

    @Test
    void takesABackslashEndingThePatternAsWritten()
    {
        assertEquals(term("end\\"), LikePatterns.query(FIELD, "end\\"));
        assertEquals(wildcard("*end\\\\"), LikePatterns.query(FIELD, "%end\\"));
    }

    @Test
    void matchesAPatternWithoutWildcardsAsItsText()
    {
        assertEquals(term("exact"), LikePatterns.query(FIELD, "exact"));
        assertEquals(term(""), LikePatterns.query(FIELD, ""));
    }

    private static Query wildcard(final String text)
    {
        return new WildcardQuery(new Term(FIELD, text));
    }

    private static Query term(final String text)
    {
        return new TermQuery(new Term(FIELD, text));
    }
}
