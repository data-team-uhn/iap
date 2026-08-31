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
package io.uhndata.iap.entities.internal;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QueryBuilder}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class QueryBuilderTest
{
    private static final String SUBMISSION = "sub:Submission";

    private static final String SCOPE = "/Submissions";

    private static final String BASE_QUERY =
        "select n.* from [sub:Submission] as n where isdescendantnode(n, '/Submissions')";

    private static final String ORDER = " order by n.[jcr:created] ASC";

    @Test
    public void minimalQueryListsTypeUnderScopeOrderedByCreation()
    {
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, SCOPE).build();
        Assertions.assertEquals(BASE_QUERY + ORDER, query.statement());
        Assertions.assertEquals(Map.of(), query.bindings());
    }

    @Test
    public void filtersAreAppendedAsConditions()
    {
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(
                new Filter("status", "=", "draft"),
                new Filter("title", "LIKE", "%consent%"),
                new Filter("schemaVersion", "IS NOT NULL", null)))
            .build();
        Assertions.assertEquals(BASE_QUERY
            + " and (n.[status] = $p0)"
            + " and (n.[title] LIKE $p1)"
            // A valueless operator binds nothing, so it consumes no variable either
            + " and (n.[schemaVersion] IS NOT NULL)"
            + ORDER, query.statement());
        Assertions.assertEquals(Map.of("p0", "draft", "p1", "%consent%"), query.bindings());
    }

    @Test
    public void caseInsensitiveLikeLowercasesBothSides()
    {
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(new Filter("title", "ILIKE", "%CARdiac's%")))
            .build();
        Assertions.assertEquals(BASE_QUERY + " and (LOWER(n.[title]) LIKE $p0)" + ORDER, query.statement());
        // The lowercasing moved to what gets bound, since the statement no longer holds the value
        Assertions.assertEquals(Map.of("p0", "%cardiac's%"), query.bindings());
    }

    @Test
    public void negatedCaseInsensitiveLikeIsSupported()
    {
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(new Filter("title", "NOT ILIKE", "%Cardiac%")))
            .build();
        Assertions.assertEquals(BASE_QUERY + " and (not LOWER(n.[title]) LIKE $p0)" + ORDER, query.statement());
        Assertions.assertEquals(Map.of("p0", "%cardiac%"), query.bindings());
    }

    @Test
    public void negatedLikeIsSupported()
    {
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(new Filter("title", "NOT LIKE", "%Cardiac%")))
            .build();
        Assertions.assertEquals(BASE_QUERY + " and (not n.[title] LIKE $p0)" + ORDER, query.statement());
        // Not lowercased: only the case-insensitive operators transform what they bind
        Assertions.assertEquals(Map.of("p0", "%Cardiac%"), query.bindings());
    }

    @Test
    public void filtersSharingAGroupAreOredTogether()
    {
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(
                new Filter("jcr:createdBy", "=", "admin"),
                new Filter("status", "=", "submitted", "g1"),
                new Filter("title", "ILIKE", "%x%"),
                new Filter("status", "=", "in-review", "g1"),
                new Filter("status", "<>", "draft", "g2")))
            .build();
        Assertions.assertEquals(BASE_QUERY
            + " and (n.[jcr:createdBy] = $p0)"
            // The group appears at its first member's position, collecting all its members
            + " and (n.[status] = $p1 or n.[status] = $p2)"
            + " and (LOWER(n.[title]) LIKE $p3)"
            + " and (not n.[status] = $p4)"
            + ORDER, query.statement());
        // Variables are numbered in the order the conditions are written, not the order they were requested
        Assertions.assertEquals(
            Map.of("p0", "admin", "p1", "submitted", "p2", "in-review", "p3", "%x%", "p4", "draft"),
            query.bindings());
    }

    @Test
    public void notEqualsIsConvertedToNegatedEquals()
    {
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(new Filter("status", "<>", "draft")))
            .build();
        Assertions.assertEquals(BASE_QUERY + " and (not n.[status] = $p0)" + ORDER, query.statement());
        Assertions.assertEquals(Map.of("p0", "draft"), query.bindings());
    }

    @Test
    public void childFiltersJoinOnDescendants()
    {
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, SCOPE)
            .withChildFilters("sub:Review", List.of(
                new Filter("reviewer", "=", "alice"),
                new Filter("status", "<>", "approved")))
            .build();
        Assertions.assertEquals(
            "select n.* from [sub:Submission] as n"
                + " inner join [sub:Review] as c0 on isdescendantnode(c0, n)"
                + " where isdescendantnode(n, '/Submissions')"
                + " and (c0.[reviewer] = $p0)"
                + " and (not c0.[status] = $p1)"
                + ORDER, query.statement());
        Assertions.assertEquals(Map.of("p0", "alice", "p1", "approved"), query.bindings());
    }

    @Test
    public void multipleChildTypesEachJoinOnTheirOwnDescendant()
    {
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, SCOPE)
            .withChildFilters("sub:Review", List.of(new Filter("reviewer", "=", "alice")))
            .withChildFilters("sub:Signature", List.of(new Filter("signer", "=", "bob")))
            .build();
        Assertions.assertEquals(
            "select n.* from [sub:Submission] as n"
                + " inner join [sub:Review] as c0 on isdescendantnode(c0, n)"
                + " inner join [sub:Signature] as c1 on isdescendantnode(c1, n)"
                + " where isdescendantnode(n, '/Submissions')"
                + " and (c0.[reviewer] = $p0)"
                + " and (c1.[signer] = $p1)"
                + ORDER, query.statement());
        Assertions.assertEquals(Map.of("p0", "alice", "p1", "bob"), query.bindings());
    }

    @Test
    public void repeatedChildTypeMergesItsFilters()
    {
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, SCOPE)
            .withChildFilters("sub:Review", List.of(new Filter("reviewer", "=", "alice")))
            .withChildFilters("sub:Review", List.of(new Filter("status", "=", "approved")))
            .build();
        Assertions.assertEquals(
            "select n.* from [sub:Submission] as n"
                + " inner join [sub:Review] as c0 on isdescendantnode(c0, n)"
                + " where isdescendantnode(n, '/Submissions')"
                + " and (c0.[reviewer] = $p0)"
                + " and (c0.[status] = $p1)"
                + ORDER, query.statement());
        Assertions.assertEquals(Map.of("p0", "alice", "p1", "approved"), query.bindings());
    }

    @Test
    public void missingChildTypeWithoutFiltersIsIgnored()
    {
        Assertions.assertEquals(BASE_QUERY + ORDER,
            new QueryBuilder(SUBMISSION, SCOPE).withChildFilters(null, List.of()).build().statement());
        Assertions.assertEquals(BASE_QUERY + ORDER,
            new QueryBuilder(SUBMISSION, SCOPE).withChildFilters(" ", List.of()).build().statement());
        Assertions.assertEquals(BASE_QUERY + ORDER,
            new QueryBuilder(SUBMISSION, SCOPE).withChildFilters(null, null).build().statement());
        // A later no-op call doesn't clear previously added child filters
        Assertions.assertEquals(
            "select n.* from [sub:Submission] as n"
                + " inner join [sub:Review] as c0 on isdescendantnode(c0, n)"
                + " where isdescendantnode(n, '/Submissions')"
                + " and (c0.[reviewer] = $p0)"
                + ORDER,
            new QueryBuilder(SUBMISSION, SCOPE)
                .withChildFilters("sub:Review", List.of(new Filter("reviewer", "=", "alice")))
                .withChildFilters(null, null)
                .build().statement());
    }

    @Test
    public void childFiltersWithoutChildTypeAreRejected()
    {
        final QueryBuilder builder = new QueryBuilder(SUBMISSION, SCOPE);
        final List<Filter> filters = List.of(new Filter("reviewer", "=", "alice"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> builder.withChildFilters(null, filters));
    }

    @Test
    public void fullTextSearchIsAppended()
    {
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, SCOPE).withFullText("tumor").build();
        Assertions.assertEquals(BASE_QUERY + " and contains(n.*, $p0)" + ORDER, query.statement());
        Assertions.assertEquals(Map.of("p0", "tumor"), query.bindings());
        Assertions.assertEquals(BASE_QUERY + ORDER,
            new QueryBuilder(SUBMISSION, SCOPE).withFullText(" ").build().statement());
        Assertions.assertEquals(BASE_QUERY + ORDER,
            new QueryBuilder(SUBMISSION, SCOPE).withFullText(null).build().statement());
    }

    @Test
    public void aQuoteInAValueIsBoundRatherThanEscaped()
    {
        // The value never reaches the statement, so there is nothing to escape and nothing to get wrong. Before
        // binding, this needed the quote doubling that is JCR-SQL2's only string escape, and a backslash escape
        // instead was a parse error that turned an ordinary surname into a failed query.
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(new Filter("title", "=", "O'Brien"))).build();
        Assertions.assertEquals(BASE_QUERY + " and (n.[title] = $p0)" + ORDER, query.statement());
        Assertions.assertEquals(Map.of("p0", "O'Brien"), query.bindings());
    }

    @Test
    public void fullTextSearchStillEscapesItsOwnGrammar()
    {
        // Binding removes the string literal's escaping, not the full text grammar's: the grammar is applied to
        // whatever the variable holds, so a backslash still has to be doubled to keep the term inert
        final QueryBuilder.BoundQuery query =
            new QueryBuilder(SUBMISSION, SCOPE).withFullText("O'Brien \\ ties").build();
        Assertions.assertEquals(BASE_QUERY + " and contains(n.*, $p0)" + ORDER, query.statement());
        Assertions.assertEquals(Map.of("p0", "O\\'Brien \\\\ ties"), query.bindings());
    }

    @Test
    public void fullTextSearchIgnoresSurroundingWhitespace()
    {
        // A full text expression has to start with a term, so a leading space -- what a paste into the search box
        // leaves in front of what was typed -- used to fail the whole listing as a bad request. The space between
        // the words is the parser's own separator and is left as it is.
        Assertions.assertEquals(Map.of("p0", "renal biopsy"),
            new QueryBuilder(SUBMISSION, SCOPE).withFullText("  renal biopsy \t").build().bindings());
    }

    @Test
    public void fullTextSearchEscapesApostrophesThatWouldOpenAPhrase()
    {
        // An apostrophe opens a quoted phrase for the full text parser exactly as the double quote does, and that
        // is true of a bound value too: what the variable holds is handed to the full text parser as it is, so an
        // unescaped apostrophe would leave it looking for a phrase that never ends
        Assertions.assertEquals(Map.of("p0", "it\\'s"),
            new QueryBuilder(SUBMISSION, SCOPE).withFullText("it's").build().bindings());
    }

    @Test
    public void fullTextSearchEscapesQuotesThatWouldOpenAPhrase()
    {
        // The double quote opens a phrase in the full text grammar, so an odd number of them -- a
        // measurement, an inch mark, ordinary typing -- would leave one unterminated and fail the
        // whole query to parse rather than merely searching oddly
        Assertions.assertEquals(Map.of("p0", "2\\\" pipe"),
            new QueryBuilder(SUBMISSION, SCOPE).withFullText("2\" pipe").build().bindings());
    }

    @Test
    public void fullTextSearchEscapesTheEscapeBeforeTheQuote()
    {
        // Order matters: doubling the backslashes after escaping the quote would turn the escape
        // this just added back into a literal backslash, re-opening the phrase it closed
        Assertions.assertEquals(Map.of("p0", "a\\\\\\\" b"),
            new QueryBuilder(SUBMISSION, SCOPE).withFullText("a\\\" b").build().bindings());
    }

    @Test
    public void sortingCanBeCustomized()
    {
        Assertions.assertEquals(BASE_QUERY + " order by n.[jcr:lastModified] DESC",
            new QueryBuilder(SUBMISSION, SCOPE).withSort("jcr:lastModified", true).build().statement());
        Assertions.assertEquals(BASE_QUERY + " order by n.[jcr:created] DESC",
            new QueryBuilder(SUBMISSION, SCOPE).withSort(null, true).build().statement());
        Assertions.assertEquals(BASE_QUERY + " order by n.[jcr:created] DESC",
            new QueryBuilder(SUBMISSION, SCOPE).withSort(" ", true).build().statement());
    }

    @Test
    public void onlyTheScopePathIsStillWrittenIntoTheStatement()
    {
        // isdescendantnode takes a path rather than a static operand, so JCR-SQL2 will not accept a bind variable
        // there and the scope path keeps the quote doubling. Every other value is bound, backslash included: a
        // backslash needs no escaping in a comparison, and only the full text grammar makes it mean anything.
        final QueryBuilder.BoundQuery query = new QueryBuilder(SUBMISSION, "/Sub'missions")
            .withFilters(List.of(new Filter("title", "=", "It's a \\ test"), new Filter("status", "=", null)))
            .withFullText("some'text")
            .build();
        Assertions.assertEquals(
            "select n.* from [sub:Submission] as n where isdescendantnode(n, '/Sub''missions')"
                + " and (n.[title] = $p0)"
                + " and (n.[status] = $p1)"
                + " and contains(n.*, $p2)"
                + ORDER, query.statement());
        Assertions.assertEquals(
            Map.of("p0", "It's a \\ test", "p1", "", "p2", "some\\'text"), query.bindings());
    }

    @Test
    public void theSameShapeOfRequestAlwaysProducesTheSameStatement()
    {
        // What a caller sends no longer changes the statement, only what is bound into it -- which is the property
        // that makes the statement safe to log, and the values not
        final QueryBuilder.BoundQuery first = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(new Filter("owner", "=", "alice"))).build();
        final QueryBuilder.BoundQuery second = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(new Filter("owner", "=", "'; drop--"))).build();
        Assertions.assertEquals(first.statement(), second.statement());
        Assertions.assertNotEquals(first.bindings(), second.bindings());
        Assertions.assertEquals(Map.of("p0", "'; drop--"), second.bindings());
    }

    @Test
    public void buildingTwiceRepeatsNeitherTheStatementNorTheBindings()
    {
        // The bindings are collected per call, so a builder reused for a second statement does not accumulate the
        // first one's variables
        final QueryBuilder builder = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(new Filter("owner", "=", "alice")));
        final QueryBuilder.BoundQuery first = builder.build();
        final QueryBuilder.BoundQuery second = builder.build();
        Assertions.assertEquals(first.statement(), second.statement());
        Assertions.assertEquals(first.bindings(), second.bindings());
        Assertions.assertEquals(1, second.bindings().size());
    }

    @Test
    public void aQueryWithoutAScopeIsRejected()
    {
        // Only the scope path is still written into the statement, so it is the only value left that has to be
        // there: without one the query would list the whole repository
        Assertions.assertThrows(NullPointerException.class, () -> new QueryBuilder(SUBMISSION, null));
    }

    @Test
    public void invalidNamesAreRejected()
    {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new QueryBuilder(null, SCOPE));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new QueryBuilder("sub:Sub'mission", SCOPE));
        final QueryBuilder builder = new QueryBuilder(SUBMISSION, SCOPE);
        Assertions.assertThrows(IllegalArgumentException.class, () -> builder.withSort("jcr:created] desc", false));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> builder.withChildFilters("sub:Review] as x", List.of()));
        final QueryBuilder withBadFilter = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(new Filter("a] is null or n.[b", "=", "x")));
        Assertions.assertThrows(IllegalArgumentException.class, withBadFilter::build);
    }
}
