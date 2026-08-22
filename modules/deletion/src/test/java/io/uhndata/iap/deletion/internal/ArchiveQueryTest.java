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
package io.uhndata.iap.deletion.internal;

import java.lang.reflect.Constructor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ArchiveQuery}.
 *
 * @version $Id$
 */
class ArchiveQueryTest
{
    @Test
    void utilityClassCannotBeInstantiatedMeaningfully() throws Exception
    {
        final Constructor<ArchiveQuery> constructor = ArchiveQuery.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }

    @Test
    void entriesAreScopedToTheSubtreeAndOrderedNewestFirstByDefault()
    {
        final String statement = ArchiveQuery.entries("/Archive", null, ArchiveQuery.DEFAULT_SORT, true);
        assertTrue(statement.contains("FROM [iap:ArchiveEntry]"), statement);
        assertTrue(statement.contains("ISDESCENDANTNODE(entry, '/Archive')"), statement);
        assertTrue(statement.endsWith("ORDER BY entry.[jcr:created] DESC"), statement);
    }

    @Test
    void ascendingIsAskedForExplicitly()
    {
        assertTrue(ArchiveQuery.entries("/Archive", null, "deletedBy", false)
            .endsWith("ORDER BY entry.[deletedBy] ASC"));
    }

    @Test
    void aBlankFilterAddsNoCondition()
    {
        assertFalse(ArchiveQuery.entries("/Archive", "   ", "deletedBy", true).contains("LIKE"));
    }

    @Test
    void aFilterMatchesEitherThePathOrTheUser()
    {
        final String statement = ArchiveQuery.entries("/Archive", "Smith", ArchiveQuery.DEFAULT_SORT, true);
        // Lower-cased on both sides, so the match ignores case without needing the index to
        assertTrue(statement.contains("LOWER(entry.[requestedPath]) LIKE '%smith%'"), statement);
        assertTrue(statement.contains("LOWER(entry.[deletedBy]) LIKE '%smith%'"), statement);
    }

    @Test
    void anUnknownSortColumnFallsBackInsteadOfBeingInterpolated()
    {
        // The column cannot be a bound variable, so anything unrecognised is refused rather than escaped
        final String statement = ArchiveQuery.entries("/Archive", null, "jcr:created] ; DROP", true);
        assertTrue(statement.endsWith("ORDER BY entry.[jcr:created] DESC"), statement);
        assertFalse(statement.contains("DROP"), statement);
    }

    @Test
    void everySortableColumnIsAccepted()
    {
        ArchiveQuery.SORTABLE.forEach(column ->
            assertTrue(ArchiveQuery.entries("/Archive", null, column, false)
                .endsWith("ORDER BY entry.[" + column + "] ASC")));
    }

    @Test
    void aQuoteInTheFilterCannotCloseTheLiteral()
    {
        final String statement = ArchiveQuery.entries("/Archive", "o'brien", ArchiveQuery.DEFAULT_SORT, true);
        assertTrue(statement.contains("'%o''brien%'"), statement);
    }

    @Test
    void wildcardsInTheFilterAreMatchedLiterally()
    {
        // Otherwise typing a % would quietly turn the filter into a different query
        final String statement = ArchiveQuery.entries("/Archive", "100%_x", ArchiveQuery.DEFAULT_SORT, true);
        assertTrue(statement.contains("'%100\\%\\_x%'"), statement);
    }

    @Test
    void aBackslashInTheFilterEscapesItself()
    {
        assertEquals("a\\\\b", ArchiveQuery.likePattern("a\\b"));
    }

    @Test
    void aQuoteInThePathCannotCloseTheLiteralEither()
    {
        assertTrue(ArchiveQuery.all("/Arch've").contains("ISDESCENDANTNODE(entry, '/Arch''ve')"));
    }

    @Test
    void countingSinceComparesAgainstADate()
    {
        final String statement = ArchiveQuery.createdSince("/Archive", "2026-08-14T00:00:00.000+00:00");
        assertTrue(statement.contains("entry.[jcr:created] >= CAST('2026-08-14T00:00:00.000+00:00' AS DATE)"),
            statement);
    }

    @Test
    void timestampsArePrintedWithANumericZeroOffset()
    {
        // The platform's ruling: a zero offset reads +00:00, never Z
        assertEquals("1970-01-01T00:00:00.000+00:00", ArchiveQuery.timestamp(0));
    }
}
