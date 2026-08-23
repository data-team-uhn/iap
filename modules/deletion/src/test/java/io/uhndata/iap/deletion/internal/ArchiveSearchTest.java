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
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ArchiveSearch}, against a real repository so that the statements in
 * {@link ArchiveQuery} are executed rather than only inspected.
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class ArchiveSearchTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private Session session;

    @BeforeEach
    void setup() throws Exception
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        NodeTypeDefinitionScanner.get().register(this.session, List.of("SLING-INF/nodetypes/deletion.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.session.getRootNode().addNode("Archive", "del:Archive");
        this.session.save();
    }

    /** Creates an entry in a bucket, the way the archive actually stores them. */
    private Node entry(final String bucket, final String name, final String user, final String path)
        throws Exception
    {
        final Node parent = this.session.nodeExists("/Archive/" + bucket)
            ? this.session.getNode("/Archive/" + bucket)
            : this.session.getNode("/Archive").addNode(bucket, "del:Archive");
        final Node entry = parent.addNode(name, "del:ArchiveEntry");
        entry.setProperty("deletedBy", user);
        entry.setProperty("requestedPath", path);
        this.session.save();
        return entry;
    }

    @Test
    void utilityClassCannotBeInstantiatedMeaningfully() throws Exception
    {
        final Constructor<ArchiveSearch> constructor = ArchiveSearch.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }

    @Test
    void anEmptyArchiveCountsZero() throws Exception
    {
        final ArchiveSearch.Count count =
            ArchiveSearch.count(this.session, ArchiveQuery.all("/Archive"), ArchiveSearch.MAX_SCAN);
        assertEquals(0, count.value());
        assertFalse(count.approximate());
    }

    @Test
    void entriesAreFoundThroughTheBucketsTheyAreStoredIn() throws Exception
    {
        // Nothing is a direct child of /Archive, which is exactly why a listing has to be a query
        this.entry("ab", "one", "alice", "/content/one");
        this.entry("cd", "two", "bob", "/content/two");
        assertEquals(2,
            ArchiveSearch.count(this.session, ArchiveQuery.all("/Archive"), ArchiveSearch.MAX_SCAN).value());
    }

    @Test
    void aFilterNarrowsToOneEntry() throws Exception
    {
        this.entry("ab", "one", "alice", "/content/one");
        this.entry("cd", "two", "bob", "/content/two");
        final ArchiveSearch.Page page = ArchiveSearch.page(this.session,
            ArchiveQuery.entries("/Archive", "BOB", ArchiveQuery.DEFAULT_SORT, true), 0, 10);
        assertEquals(List.of("/Archive/cd/two"), page.paths());
        assertEquals(1, page.total().value());
    }

    @Test
    void theFilterAlsoMatchesTheDeletedPath() throws Exception
    {
        this.entry("ab", "one", "alice", "/content/keepme");
        this.entry("cd", "two", "bob", "/content/other");
        final ArchiveSearch.Page page = ArchiveSearch.page(this.session,
            ArchiveQuery.entries("/Archive", "keepme", ArchiveQuery.DEFAULT_SORT, true), 0, 10);
        assertEquals(List.of("/Archive/ab/one"), page.paths());
    }

    @Test
    void sortingIsAppliedByTheRepository() throws Exception
    {
        this.entry("ab", "one", "carol", "/content/one");
        this.entry("cd", "two", "alice", "/content/two");
        final ArchiveSearch.Page ascending = ArchiveSearch.page(this.session,
            ArchiveQuery.entries("/Archive", null, "deletedBy", false), 0, 10);
        assertEquals(List.of("/Archive/cd/two", "/Archive/ab/one"), ascending.paths());
        final ArchiveSearch.Page descending = ArchiveSearch.page(this.session,
            ArchiveQuery.entries("/Archive", null, "deletedBy", true), 0, 10);
        assertEquals(List.of("/Archive/ab/one", "/Archive/cd/two"), descending.paths());
    }

    @Test
    void aPageSkipsAndStopsWhileStillCountingEverything() throws Exception
    {
        this.entry("ab", "a", "u", "/content/a");
        this.entry("ab", "b", "u", "/content/b");
        this.entry("ab", "c", "u", "/content/c");
        final ArchiveSearch.Page page = ArchiveSearch.page(this.session,
            ArchiveQuery.entries("/Archive", null, "requestedPath", false), 1, 1);
        assertEquals(List.of("/Archive/ab/b"), page.paths());
        // The page is one row, but the caller still learns there are three
        assertEquals(3, page.total().value());
        assertFalse(page.total().approximate());
    }

    @Test
    void countingStopsAtTheBoundAndSaysSo() throws Exception
    {
        this.entry("ab", "a", "u", "/content/a");
        this.entry("ab", "b", "u", "/content/b");
        this.entry("ab", "c", "u", "/content/c");
        final ArchiveSearch.Count count = ArchiveSearch.count(this.session, ArchiveQuery.all("/Archive"), 2);
        assertEquals(2, count.value());
        assertTrue(count.approximate());
    }

    @Test
    void aPageAlsoStopsAtTheBound() throws Exception
    {
        this.entry("ab", "a", "u", "/content/a");
        this.entry("ab", "b", "u", "/content/b");
        this.entry("ab", "c", "u", "/content/c");
        final ArchiveSearch.Page page = ArchiveSearch.page(this.session,
            ArchiveQuery.entries("/Archive", null, "requestedPath", false), 0, 10, 2);
        assertEquals(2, page.paths().size());
        assertTrue(page.total().approximate());
    }

    @Test
    void aSubtreeListsOnlyItsOwnEntries() throws Exception
    {
        this.entry("ab", "one", "alice", "/content/one");
        this.entry("cd", "two", "bob", "/content/two");
        assertEquals(1,
            ArchiveSearch.count(this.session, ArchiveQuery.all("/Archive/ab"), ArchiveSearch.MAX_SCAN).value());
    }
}
