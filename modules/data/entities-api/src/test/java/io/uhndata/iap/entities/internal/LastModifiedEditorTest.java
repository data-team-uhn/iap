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

import java.io.StringReader;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.jcr.Jcr;
import org.apache.jackrabbit.oak.jcr.repository.RepositoryImpl;
import org.apache.jackrabbit.oak.plugins.memory.GenericPropertyState;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EditorHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LastModifiedEditor} and {@link LastModifiedEditorProvider}, committing through a real
 * repository.
 *
 * @version $Id$
 * @since 0.1.0
 */
class LastModifiedEditorTest
{
    private static final String LAST_MODIFIED = "jcr:lastModified";

    private static final String AUTHOR = "author";

    private static final String RECORD = "/records/record";

    private static final String TEXT = "text";

    private static final String CHANGED = "changed";

    private static final String PART = "test:Part";

    private static final String NOTES = "notes";

    private static final String TYPES = String.join("\n",
        "<data = 'https://iap.test/data'>",
        "<test = 'https://iap.test/test'>",
        "[data:Entity] > nt:base, mix:lastModified abstract",
        "  - * (UNDEFINED)",
        "  - status (STRING) IGNORE",
        "  + log (test:Log) = test:Log IGNORE",
        "  + * (nt:base) = test:Part",
        "[test:Record] > data:Entity",
        "[test:Part] > nt:base, mix:lastModified",
        "  - * (UNDEFINED)",
        "  + * (nt:base) = test:Part",
        "[test:Folder] > nt:base",
        "  - * (UNDEFINED)",
        "  + * (nt:base) = test:Part",
        "[test:Log] > nt:base, mix:lastModified",
        "  - * (UNDEFINED)",
        "  + * (nt:base) = test:Part",
        // Two definitions admit a test:Log child, and only one of them leaves it out
        "[test:Mixed] > nt:base, mix:lastModified",
        "  - * (UNDEFINED)",
        "  + * (test:Log) = test:Log IGNORE",
        "  + * (nt:base) = test:Part",
        // Each child is admitted by one definition only, and only one of them leaves it out
        "[test:Journal] > nt:base, mix:lastModified",
        "  + * (test:Log) = test:Log IGNORE",
        "  + * (test:Part) = test:Part",
        // Leaves out every property it does not name
        "[test:Notes] > nt:base, mix:lastModified",
        "  - * (UNDEFINED) IGNORE");

    private final LastModifiedEditorProvider provider = new LastModifiedEditorProvider();

    private MemoryNodeStore store;

    private RepositoryImpl repository;

    private Session session;

    /** When the fixture was saved, to the millisecond a stamp records. */
    private Instant fixtureSaved;

    @BeforeEach
    void setUp() throws Exception
    {
        this.store = new MemoryNodeStore();
        this.repository = (RepositoryImpl) new Jcr(new Oak(this.store)).with(this.provider).createRepository();
        this.session = this.repository.login(new SimpleCredentials("admin", "admin".toCharArray()));
        CndImporter.registerNodeTypes(new StringReader(TYPES), this.session);
        final Node records = this.session.getRootNode().addNode("records", "test:Folder");
        final Node record = records.addNode("record", "test:Record");
        record.addNode("part", PART).addNode("detail", PART);
        record.addNode("folder", "test:Folder").addNode("part", PART).setProperty(TEXT, "original");
        record.addNode("log", "test:Log").addNode("entry", PART);
        records.addNode("other", "test:Record").addNode("log", PART);
        this.session.getRootNode().addNode("holder", PART).addNode("record", "test:Record").addNode("part", PART);
        this.session.getRootNode().addNode("mixed", "test:Mixed").addNode("entry", "test:Log");
        this.session.getRootNode().addNode("plain", "nt:unstructured").addMixin("mix:lastModified");
        this.session.getRootNode().addNode(NOTES, "test:Notes");
        final Node journal = this.session.getRootNode().addNode("journal", "test:Journal");
        journal.addNode("log", "test:Log");
        journal.addNode("page", PART);
        this.session.save();
        this.fixtureSaved = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        // A stamp records milliseconds, so the commits under test must land in a later one than the fixture's
        while (!Instant.now().truncatedTo(ChronoUnit.MILLIS).isAfter(this.fixtureSaved)) {
            Thread.onSpinWait();
        }
    }

    @AfterEach
    void tearDown()
    {
        this.session.logout();
        this.repository.shutdown();
    }

    @Test
    void stampsTheNodeThatChangedWithTheTimeOfTheCommitAndItsAuthor() throws Exception
    {
        final Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        commitDirectly(root -> record(root).setProperty(TEXT, CHANGED));

        final Instant stamp = lastModified(RECORD);
        assertFalse(stamp.isBefore(start) || stamp.isAfter(Instant.now()));
        assertEquals(AUTHOR, this.session.getNode(RECORD).getProperty("jcr:lastModifiedBy").getString());
    }

    @Test
    void stampsEveryAncestorUpToTheEntity() throws RepositoryException
    {
        change(RECORD + "/part/detail");

        assertTrue(stamped(RECORD + "/part"));
        assertTrue(stamped(RECORD));
        assertFalse(stamped("/records/other"));
    }

    @Test
    void passesThroughAnAncestorThatRecordsNoModification() throws RepositoryException
    {
        change(RECORD + "/folder/part");

        assertFalse(this.session.getNode(RECORD + "/folder").hasProperty(LAST_MODIFIED));
        assertTrue(stamped(RECORD));
    }

    @Test
    void countsARemovedProperty() throws RepositoryException
    {
        this.session.getNode(RECORD + "/folder/part").getProperty(TEXT).remove();
        this.session.save();

        assertTrue(stamped(RECORD));
    }

    @Test
    void stopsAtTheEntity() throws RepositoryException
    {
        change("/holder/record/part");

        assertTrue(stamped("/holder/record"));
        assertFalse(stamped("/holder"));
    }

    @Test
    void ignoresAPropertyLeftOutOfTheNodesVersions() throws RepositoryException
    {
        this.session.getNode(RECORD).setProperty("status", CHANGED);
        this.session.save();

        assertFalse(stamped(RECORD));
    }

    @Test
    void keepsAChangeInsideAChildLeftOutOfTheVersionsBelowIt() throws RepositoryException
    {
        change(RECORD + "/log/entry");

        assertTrue(stamped(RECORD + "/log"));
        assertFalse(stamped(RECORD));
    }

    @Test
    void choosesAChildsDefinitionByItsType() throws RepositoryException
    {
        // Named like the left out child, but of a type only the definition of the record's parts admits
        change("/records/other/log");

        assertTrue(stamped("/records/other"));
    }

    @Test
    void choosesAChildsResidualDefinitionByItsType() throws RepositoryException
    {
        change("/journal/log");
        assertFalse(stamped("/journal"));

        change("/journal/page");
        assertTrue(stamped("/journal"));
    }

    @Test
    void countsAChildThatAnyDefinitionKeepsInTheVersions() throws RepositoryException
    {
        change("/mixed/entry");

        assertTrue(stamped("/mixed"));
    }

    @Test
    void countsAnAddedChildAndKeepsTheStampItCameWith() throws Exception
    {
        // As an import or a copy brings one
        final String imported = "2020-01-02T03:04:05.000Z";
        commitDirectly(root -> record(root).child("imported")
            .setProperty("jcr:primaryType", PART, Type.NAME)
            .setProperty(GenericPropertyState.dateProperty(LAST_MODIFIED, imported)));

        assertTrue(stamped(RECORD));
        assertEquals(Instant.parse(imported), lastModified(RECORD + "/imported"));
    }

    @Test
    void countsARemovedChild() throws RepositoryException
    {
        this.session.getNode(RECORD + "/part").remove();
        this.session.save();

        assertTrue(stamped(RECORD));
    }

    @Test
    void ignoresRemovingAChildLeftOutOfTheVersions() throws RepositoryException
    {
        this.session.getNode(RECORD + "/log").remove();
        this.session.save();

        assertFalse(stamped(RECORD));
    }

    @Test
    void stampsANodeThatRecordsModificationsThroughAMixin() throws RepositoryException
    {
        change("/plain");

        assertTrue(stamped("/plain"));
    }

    @Test
    void ignoresAPropertyOnlyALeftOutDefinitionAdmits() throws RepositoryException
    {
        change("/" + NOTES);

        assertFalse(stamped("/" + NOTES));
    }

    @Test
    void countsAddingAMixin() throws RepositoryException
    {
        // The registry files the definitions of jcr:mixinTypes and jcr:uuid under other names
        this.session.getNode("/" + NOTES).addMixin("mix:referenceable");
        this.session.save();

        assertTrue(stamped("/" + NOTES));
    }

    @Test
    void ignoresOaksHiddenNodes() throws Exception
    {
        commitDirectly(root -> record(root).getChildNode("part").child(":index"));

        assertFalse(stamped(RECORD + "/part"));
    }

    @Test
    void toleratesANodeWithoutAType() throws Exception
    {
        commitDirectly(root -> record(root).child("loose"));
        commitDirectly(root -> record(root).child("loose").setProperty(TEXT, CHANGED));

        assertTrue(stamped(RECORD));
    }

    /**
     * Commits to the node store itself, which can write what a session cannot. The commit is made as a user other
     * than the one who created the fixture.
     *
     * @param change the change to make, given the root builder
     */
    private void commitDirectly(final Consumer<NodeBuilder> change) throws CommitFailedException, RepositoryException
    {
        final NodeBuilder root = this.store.getRoot().builder();
        change.accept(root);
        this.store.merge(root, new EditorHook(this.provider), new CommitInfo("test", AUTHOR));
        this.session.refresh(false);
    }

    private static NodeBuilder record(final NodeBuilder root)
    {
        return root.getChildNode("records").getChildNode("record");
    }

    private void change(final String path) throws RepositoryException
    {
        this.session.getNode(path).setProperty(TEXT, CHANGED);
        this.session.save();
    }

    /**
     * Whether a node was stamped by a commit under test. Nodes the fixture created carry the earlier time of their
     * creation.
     *
     * @param path the node to check
     * @return {@code true} if it was last modified after the fixture was saved
     */
    private boolean stamped(final String path) throws RepositoryException
    {
        return this.session.getNode(path).hasProperty(LAST_MODIFIED) && lastModified(path).isAfter(this.fixtureSaved);
    }

    private Instant lastModified(final String path) throws RepositoryException
    {
        return this.session.getNode(path).getProperty(LAST_MODIFIED).getDate().toInstant();
    }
}
