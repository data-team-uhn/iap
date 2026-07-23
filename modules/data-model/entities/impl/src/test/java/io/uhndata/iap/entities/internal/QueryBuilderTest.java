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

    @Test
    public void minimalQueryListsTypeUnderScopeOrderedByCreation()
    {
        Assertions.assertEquals(BASE_QUERY + " order by n.[jcr:created] ASC",
            new QueryBuilder(SUBMISSION, SCOPE).build());
    }

    @Test
    public void filtersAreAppendedAsConditions()
    {
        final String query = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(
                new Filter("status", "=", "draft"),
                new Filter("title", "LIKE", "%consent%"),
                new Filter("schemaVersion", "IS NOT NULL", null)))
            .build();
        Assertions.assertEquals(BASE_QUERY
            + " and n.[status] = 'draft'"
            + " and n.[title] LIKE '%consent%'"
            + " and n.[schemaVersion] IS NOT NULL"
            + " order by n.[jcr:created] ASC", query);
    }

    @Test
    public void caseInsensitiveLikeLowercasesBothSides()
    {
        final String query = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(new Filter("title", "ILIKE", "%CARdiac's%")))
            .build();
        Assertions.assertEquals(BASE_QUERY + " and LOWER(n.[title]) LIKE '%cardiac\\'s%' order by n.[jcr:created] ASC",
            query);
    }

    @Test
    public void notEqualsIsConvertedToNegatedEquals()
    {
        final String query = new QueryBuilder(SUBMISSION, SCOPE)
            .withFilters(List.of(new Filter("status", "<>", "draft")))
            .build();
        Assertions.assertEquals(BASE_QUERY + " and not n.[status] = 'draft' order by n.[jcr:created] ASC", query);
    }

    @Test
    public void childFiltersJoinOnDescendants()
    {
        final String query = new QueryBuilder(SUBMISSION, SCOPE)
            .withChildFilters("sub:Review", List.of(
                new Filter("reviewer", "=", "alice"),
                new Filter("status", "<>", "approved")))
            .build();
        Assertions.assertEquals(
            "select n.* from [sub:Submission] as n"
                + " inner join [sub:Review] as c on isdescendantnode(c, n)"
                + " where isdescendantnode(n, '/Submissions')"
                + " and c.[reviewer] = 'alice'"
                + " and not c.[status] = 'approved'"
                + " order by n.[jcr:created] ASC", query);
    }

    @Test
    public void missingChildTypeWithoutFiltersIsIgnored()
    {
        Assertions.assertEquals(BASE_QUERY + " order by n.[jcr:created] ASC",
            new QueryBuilder(SUBMISSION, SCOPE).withChildFilters(null, List.of()).build());
        Assertions.assertEquals(BASE_QUERY + " order by n.[jcr:created] ASC",
            new QueryBuilder(SUBMISSION, SCOPE).withChildFilters(" ", List.of()).build());
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
        Assertions.assertEquals(BASE_QUERY + " and contains(n.*, 'tumor') order by n.[jcr:created] ASC",
            new QueryBuilder(SUBMISSION, SCOPE).withFullText("tumor").build());
        Assertions.assertEquals(BASE_QUERY + " order by n.[jcr:created] ASC",
            new QueryBuilder(SUBMISSION, SCOPE).withFullText(" ").build());
        Assertions.assertEquals(BASE_QUERY + " order by n.[jcr:created] ASC",
            new QueryBuilder(SUBMISSION, SCOPE).withFullText(null).build());
    }

    @Test
    public void sortingCanBeCustomized()
    {
        Assertions.assertEquals(BASE_QUERY + " order by n.[jcr:lastModified] DESC",
            new QueryBuilder(SUBMISSION, SCOPE).withSort("jcr:lastModified", true).build());
        Assertions.assertEquals(BASE_QUERY + " order by n.[jcr:created] DESC",
            new QueryBuilder(SUBMISSION, SCOPE).withSort(null, true).build());
        Assertions.assertEquals(BASE_QUERY + " order by n.[jcr:created] DESC",
            new QueryBuilder(SUBMISSION, SCOPE).withSort(" ", true).build());
    }

    @Test
    public void valuesAreEscaped()
    {
        final String query = new QueryBuilder(SUBMISSION, "/Sub'missions")
            .withFilters(List.of(new Filter("title", "=", "It's a \\ test"), new Filter("status", "=", null)))
            .withFullText("some'text")
            .build();
        Assertions.assertEquals(
            "select n.* from [sub:Submission] as n where isdescendantnode(n, '/Sub\\'missions')"
                + " and n.[title] = 'It\\'s a \\\\ test'"
                + " and n.[status] = ''"
                + " and contains(n.*, 'some\\'text')"
                + " order by n.[jcr:created] ASC", query);
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
