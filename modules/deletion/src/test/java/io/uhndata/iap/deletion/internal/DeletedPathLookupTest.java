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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
 * Tests for {@link DeletedPathLookup}.
 *
 * <p>
 * The ranking cases need two entries whose creation timestamps differ, and {@code jcr:created} is autocreated and
 * protected, so they are separated by saving them apart rather than by setting the property.
 * </p>
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class DeletedPathLookupTest
{
    /** The path most of these deletions are of; named because checkstyle counts repeated literals. */
    private static final String ONE = "/Submissions/one";

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

    /** One deletion of one path, archived under its own entry. */
    private Node entry(final String name, final String deletedBy, final String originalPath) throws Exception
    {
        final Node bucket = this.session.nodeExists("/Archive/ab")
            ? this.session.getNode("/Archive/ab")
            : this.session.getNode("/Archive").addNode("ab", "del:Archive");
        final Node entry = bucket.addNode(name, "del:ArchiveEntry");
        entry.setProperty("deletedBy", deletedBy);
        entry.setProperty("requestedPath", originalPath);
        final Node item = entry.addNode("item", "del:DeletedItem");
        item.setProperty("originalPath", originalPath);
        // Saved here rather than by the caller so that two entries never share a jcr:created
        this.session.save();
        return entry;
    }

    @Test
    void aPathThatIsNotAbsoluteHasNoCandidates()
    {
        assertEquals(List.of(), DeletedPathLookup.candidates("Submissions/one"));
        assertEquals(List.of(), DeletedPathLookup.candidates(null));
        assertEquals(List.of(), DeletedPathLookup.candidates("/"));
        assertEquals(List.of(), DeletedPathLookup.candidates("///"));
    }

    @Test
    void candidatesClimbToTheRoot()
    {
        assertEquals(List.of("/Submissions/one/answers", ONE, "/Submissions"),
            DeletedPathLookup.candidates("/Submissions/one/answers"));
    }

    @Test
    void candidatesPeelSelectorsAndExtensionsBeforeSegments()
    {
        assertEquals(List.of("/Submissions/one.4.json", "/Submissions/one.4", ONE, "/Submissions"),
            DeletedPathLookup.candidates("/Submissions/one.4.json"));
    }

    @Test
    void aDotOpeningASegmentIsPartOfTheName()
    {
        // Peeling it would produce a path ending in a slash, and the name really is ".hidden"
        assertEquals(List.of("/Submissions/.hidden", "/Submissions"),
            DeletedPathLookup.candidates("/Submissions/.hidden"));
    }

    @Test
    void aTrailingSlashIsNotASegment()
    {
        assertEquals(List.of(ONE, "/Submissions"), DeletedPathLookup.candidates("/Submissions/one/"));
    }

    @Test
    void aPathDeeperThanTheBoundKeepsItsMostSpecificCandidates()
    {
        final String deep = IntStream.range(0, 40).mapToObj(i -> "/s" + i).collect(Collectors.joining());
        final List<String> candidates = DeletedPathLookup.candidates(deep);

        assertEquals(DeletedPathLookup.MAX_CANDIDATES, candidates.size());
        assertEquals(deep, candidates.get(0));
        // The longest ones are kept, so what is lost is the top of the tree: 23 steps up from a 40-segment path
        assertEquals(IntStream.range(0, 17).mapToObj(i -> "/s" + i).collect(Collectors.joining()),
            candidates.get(candidates.size() - 1));
    }

    @Test
    void theStatementAsksAboutEveryCandidateAndEscapesThem()
    {
        final String statement = DeletedPathLookup.statement(List.of("/a/o'brien", "/a"));

        assertTrue(statement.contains("FROM [del:DeletedItem] AS item"), statement);
        assertTrue(statement.contains("ISDESCENDANTNODE(item, '/Archive')"), statement);
        assertTrue(statement.contains("item.[originalPath] = '/a/o''brien' OR item.[originalPath] = '/a'"),
            statement);
    }

    /** A match, built directly, so that the ranking can be exercised without depending on query result order. */
    private static DeletedPathLookup.Archived match(final String originalPath, final long deletedAt)
    {
        final ZonedDateTime when = Instant.ofEpochMilli(deletedAt).atZone(ZoneId.systemDefault());
        return new DeletedPathLookup.Archived(originalPath, "/Archive/ab/x", "x", "alice", when);
    }

    @Test
    void theFirstMatchIsAlwaysTheBestSoFar()
    {
        assertTrue(match(ONE, 1000).isBetterThan(null));
    }

    @Test
    void aMoreSpecificMatchBeatsALessSpecificOneBothWaysRound()
    {
        final DeletedPathLookup.Archived specific = match(ONE, 1000);
        final DeletedPathLookup.Archived general = match("/Submissions", 2000);

        // Later, but less specific: recency only decides between matches of the same path
        assertTrue(specific.isBetterThan(general));
        assertFalse(general.isBetterThan(specific));
    }

    @Test
    void theMoreRecentOfTwoDeletionsOfOnePathWinsBothWaysRound()
    {
        final DeletedPathLookup.Archived older = match(ONE, 1000);
        final DeletedPathLookup.Archived newer = match(ONE, 2000);

        assertTrue(newer.isBetterThan(older));
        assertFalse(older.isBetterThan(newer));
    }

    @Test
    void anUntouchedPathIsNotFound() throws Exception
    {
        this.entry("one", "alice", ONE);

        assertTrue(DeletedPathLookup.find(this.session, "/Submissions/two").isEmpty());
    }

    @Test
    void aRelativePathIsNotLookedUpAtAll() throws Exception
    {
        this.entry("one", "alice", ONE);

        assertTrue(DeletedPathLookup.find(this.session, "relative").isEmpty());
    }

    @Test
    void aDeletedPathIsFoundWithItsEntry() throws Exception
    {
        final Node entry = this.entry("one", "alice", ONE);

        final DeletedPathLookup.Archived found = DeletedPathLookup.find(this.session, ONE).get();

        assertEquals(ONE, found.originalPath());
        assertEquals(entry.getPath(), found.entryPath());
        assertEquals("one", found.entryName());
        assertEquals("alice", found.deletedBy());
        final Calendar created = entry.getProperty("jcr:created").getDate();
        assertEquals(created.toInstant(), found.deletedAt().toInstant());
    }

    @Test
    void aPathCarryingSelectorsFindsTheResourceUnderThem() throws Exception
    {
        this.entry("one", "alice", ONE);

        assertEquals(ONE,
            DeletedPathLookup.find(this.session, "/Submissions/one.4.json").get().originalPath());
    }

    @Test
    void aPathInsideADeletedSubtreeFindsTheSubtree() throws Exception
    {
        this.entry("one", "alice", ONE);

        assertEquals(ONE,
            DeletedPathLookup.find(this.session, "/Submissions/one/answers/first").get().originalPath());
    }

    @Test
    void theMostSpecificDeletionWins() throws Exception
    {
        // The parent was deleted first, then recreated and its child deleted on its own: both cover the request,
        // and only the closer one describes what actually happened to it
        this.entry("parent", "alice", "/Submissions");
        this.entry("child", "bob", ONE);

        final DeletedPathLookup.Archived found = DeletedPathLookup.find(this.session, ONE).get();
        assertEquals(ONE, found.originalPath());
        assertEquals("bob", found.deletedBy());
    }

    @Test
    void theMostSpecificDeletionWinsWhicheverOrderTheQueryReturnsThem() throws Exception
    {
        this.entry("child", "bob", ONE);
        this.entry("parent", "alice", "/Submissions");

        assertEquals("bob", DeletedPathLookup.find(this.session, ONE).get().deletedBy());
    }

    @Test
    void theMostRecentDeletionOfTheSamePathWins() throws Exception
    {
        this.entry("first", "alice", ONE);
        this.entry("second", "bob", ONE);

        assertEquals("bob", DeletedPathLookup.find(this.session, ONE).get().deletedBy());
    }

    @Test
    void anOlderDeletionOfTheSamePathDoesNotDisplaceTheNewerOne() throws Exception
    {
        // Same pair as above with the names swapped, so that whichever order the query returns them in, one of
        // these two tests exercises the losing comparison
        this.entry("second", "bob", ONE);
        this.entry("first", "alice", ONE);

        assertEquals("alice", DeletedPathLookup.find(this.session, ONE).get().deletedBy());
    }

    @Test
    void aWrapperArchivedInsideAnotherDeletionIsNotADeletionRecord() throws Exception
    {
        // The archive can be archived: a wrapper that came along inside someone else's deleted subtree records
        // where it used to be, but nothing lives at that path any more on its account
        final Node entry = this.entry("one", "alice", "/Archive/old");
        final Node nested = entry.getNode("item").addNode("inner", "del:DeletedItem");
        nested.setProperty("originalPath", "/Submissions/ghost");
        this.session.save();

        assertEquals(Optional.empty(), DeletedPathLookup.find(this.session, "/Submissions/ghost"));
    }
}
