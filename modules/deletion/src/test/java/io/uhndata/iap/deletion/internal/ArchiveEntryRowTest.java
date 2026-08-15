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
import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

import jakarta.json.JsonObject;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ArchiveEntryRow}.
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class ArchiveEntryRowTest
{
    private final SlingContext context = new SlingContext();

    private static Calendar at(final long epochMillis)
    {
        final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(epochMillis);
        return calendar;
    }

    @Test
    void utilityClassCannotBeInstantiatedMeaningfully() throws Exception
    {
        final Constructor<ArchiveEntryRow> constructor = ArchiveEntryRow.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }

    @Test
    void describesTheEntryAndWhereItsContentsCameFrom()
    {
        this.context.create().resource("/Archive/ab/one",
            Map.of("deletedBy", "alice", "requestedPath", "/content/one", "jcr:created", at(0)));
        this.context.create().resource("/Archive/ab/one/item0", Map.of("originalPath", "/content/one"));
        this.context.create().resource("/Archive/ab/one/item1", Map.of("originalPath", "/content/dragged"));

        final JsonObject row =
            ArchiveEntryRow.of(this.context.resourceResolver().getResource("/Archive/ab/one")).build();

        assertEquals("/Archive/ab/one", row.getString("path"));
        assertEquals("alice", row.getString("deletedBy"));
        assertEquals("/content/one", row.getString("requestedPath"));
        assertEquals("1970-01-01T00:00:00.000+00:00", row.getString("created"));
        assertEquals(2, row.getInt("itemCount"));
        assertEquals(2, row.getJsonArray("originalPaths").size());
    }

    @Test
    void anEntryWithNothingArchivedUnderItReportsNoItems()
    {
        this.context.create().resource("/Archive/ab/empty",
            Map.of("deletedBy", "alice", "requestedPath", "/content/x", "jcr:created", at(0)));

        final JsonObject row =
            ArchiveEntryRow.of(this.context.resourceResolver().getResource("/Archive/ab/empty")).build();

        assertEquals(0, row.getInt("itemCount"));
        assertTrue(row.getJsonArray("originalPaths").isEmpty());
    }

    @Test
    void childrenThatRecordNoOriginalPathAreNotCounted()
    {
        // The entry's node type allows other children for extensibility; one of those says nothing
        // a reader could restore, so it is not an item
        this.context.create().resource("/Archive/ab/mixed",
            Map.of("deletedBy", "alice", "requestedPath", "/content/x", "jcr:created", at(0)));
        this.context.create().resource("/Archive/ab/mixed/item0", Map.of("originalPath", "/content/x"));
        this.context.create().resource("/Archive/ab/mixed/notes", Map.of("comment", "unrelated"));

        final JsonObject row =
            ArchiveEntryRow.of(this.context.resourceResolver().getResource("/Archive/ab/mixed")).build();

        assertEquals(1, row.getInt("itemCount"));
        assertEquals("/content/x", row.getJsonArray("originalPaths").getString(0));
    }

    @Test
    void anEntryWithoutACreationDateIsStillListed()
    {
        // Rather than dropping the row: an entry that cannot be dated can still be restored or purged,
        // and hiding it would make it unreachable
        this.context.create().resource("/Archive/ab/undated",
            Map.of("deletedBy", "alice", "requestedPath", "/content/x"));

        final JsonObject row =
            ArchiveEntryRow.of(this.context.resourceResolver().getResource("/Archive/ab/undated")).build();

        assertFalse(row.containsKey("created"));
        assertEquals("/content/x", row.getString("requestedPath"));
    }

    @Test
    void missingPropertiesReadAsEmptyRatherThanFailing()
    {
        this.context.create().resource("/Archive/ab/bare", Map.of("jcr:primaryType", "nt:unstructured"));

        final JsonObject row =
            ArchiveEntryRow.of(this.context.resourceResolver().getResource("/Archive/ab/bare")).build();

        assertEquals("", row.getString("deletedBy"));
        assertEquals("", row.getString("requestedPath"));
    }
}
