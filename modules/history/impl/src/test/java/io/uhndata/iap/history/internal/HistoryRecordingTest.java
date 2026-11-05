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
package io.uhndata.iap.history.internal;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.Value;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.PropertyDefinition;

import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.jcr.Jcr;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.spi.commit.EditorDiff;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.entities.nodetypes.NodeTypeRules;
import io.uhndata.iap.history.api.HistoryRecorder;
import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.api.RecordedEffect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests describing a change and recording it in the commit that makes it, through a real repository with the history
 * editor installed.
 *
 * @version $Id$
 * @since 0.1.0
 */
class HistoryRecordingTest
{
    /** What Sling declares before any of IAP's own types. */
    private static final String SLING_TYPES = String.join("\n",
        "<sling = 'http://sling.apache.org/jcr/sling/1.0'>",
        "[sling:Resource] mixin",
        "  - sling:resourceType (STRING)",
        "[sling:ResourceSuperType] mixin",
        "  - sling:resourceSuperType (STRING)",
        "[sling:Folder] > nt:folder",
        "  - * (UNDEFINED) multiple",
        "  - * (UNDEFINED)",
        "  + * (nt:base) = sling:Folder version");

    /** Entities and their parts, as the history sees them; one kind of entity has no identifier to file it by. */
    private static final String TEST_TYPES = String.join("\n",
        "<data = 'https://iap.uhndata.io/data/'>",
        "<test = 'https://iap.test/test'>",
        "<wf = 'https://iap.uhndata.io/wf/'>",
        "[data:Entity] > data:Content abstract",
        "[test:Request] > data:Entity, mix:referenceable",
        "  - derived (STRING) COMPUTE",
        "[test:Versioned] > data:Entity, mix:versionable",
        "[test:Unnamed] > data:Entity",
        "[test:Part] > data:Content orderable");

    private static final String REQUEST = "/Requests/one";

    private static final String DAYS = REQUEST + "/answers/days";

    private static final String VALUE = "value";

    private static final String REQUEST_TYPE = "test:Request";

    private static final String PART_TYPE = "test:Part";

    private static final String REQUESTER = "jdoe";

    private static final String SAVE = "save";

    private static final String ROLE = "role";

    private static final String CHANGED = "changed";

    private static final String ADDED = "added";

    private static final String REMOVED = "removed";

    private static final String COMPLETE = "complete";

    private MemoryNodeStore store;

    private Session session;

    private HistoryRecorder recorder;

    private String requestId;

    @BeforeEach
    void setUp() throws Exception
    {
        this.store = new MemoryNodeStore();
        this.session = new Jcr(new Oak(this.store)).with(new HistoryEditorProvider()).createRepository()
            .login(new SimpleCredentials("admin", "admin".toCharArray()));
        register(new StringReader(SLING_TYPES));
        register(cnd("SLING-INF/nodetypes/content.cnd"));
        register(new StringReader(TEST_TYPES));
        register(cnd("SLING-INF/nodetypes/history.cnd"));
        final Node request = this.session.getRootNode().addNode("Requests", "sling:Folder")
            .addNode("one", REQUEST_TYPE);
        request.addNode("answers", PART_TYPE).addNode("days", PART_TYPE).setProperty(VALUE, "3");
        this.session.save();
        this.requestId = request.getIdentifier();
        this.recorder = new HistoryRecorderImpl();
    }

    @AfterEach
    void tearDown()
    {
        this.session.logout();
    }

    @Test
    void recordsTheActionInTheCommitThatDidIt() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, SAVE).event(SAVE).build(),
            () -> this.session.getNode(DAYS).setProperty(VALUE, "5"));

        final Node action = this.session.getNode(path);
        assertEquals(REQUESTER, action.getProperty("createdBy").getString());
        assertEquals(SAVE, action.getProperty("operation").getString());
        assertEquals(SAVE, action.getProperty("event").getString());
        assertEquals("admin", action.getProperty("jcr:createdBy").getString(),
            "The record is written by whoever commits; who asked for the action is createdBy");
        assertTrue(action.getProperty(COMPLETE).getBoolean(), "No snapshot was asked for, so nothing is missing");
        final Node entry = action.getNode(this.requestId);
        assertEquals(this.requestId, entry.getProperty("subject").getString());
        assertEquals(REQUEST, entry.getProperty("subjectPath").getString());
        assertEquals(REQUEST_TYPE, entry.getProperty("subjectType").getString());
        assertEquals("updated", entry.getProperty(ROLE).getString());
        assertEquals(List.of("answers/days/value"), strings(entry, CHANGED));
        assertFalse(entry.hasProperty(ADDED));
        assertFalse(entry.hasProperty(REMOVED));
    }

    @Test
    void writesWhatItsNodeTypesWouldInsistOn() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, SAVE).build(),
            () -> this.session.getNode(DAYS).setProperty(VALUE, "5"));

        // A commit editor writes below the type checks, so nothing else would notice a missing property
        final List<Node> written = new ArrayList<>();
        for (Node node = this.session.getNode(path); !"/".equals(node.getPath()); node = node.getParent()) {
            written.add(node);
        }
        written.add(this.session.getNode(path).getNode(this.requestId));
        for (final Node node : written) {
            for (final NodeType type : allTypesOf(node)) {
                for (final PropertyDefinition definition : type.getPropertyDefinitions()) {
                    if ((definition.isMandatory() || definition.isAutoCreated()) && !"*".equals(definition.getName())) {
                        assertTrue(node.hasProperty(definition.getName()),
                            node.getPath() + " lacks " + definition.getName() + " from " + type.getName());
                    }
                }
            }
        }
    }

    @Test
    void namesABranchAddedOrRemovedByItsTopmostNode() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, SAVE).build(), () -> {
            final Node reason = this.session.getNode(REQUEST + "/answers").addNode("reason", PART_TYPE);
            reason.addNode("text", PART_TYPE).setProperty(VALUE, "Holiday");
            this.session.getNode(DAYS).remove();
        });

        final Node entry = this.session.getNode(path).getNode(this.requestId);
        assertEquals(List.of("answers/reason"), strings(entry, ADDED));
        assertEquals(List.of("answers/days"), strings(entry, REMOVED));
        assertFalse(entry.hasProperty(CHANGED));
    }

    @Test
    void namesAPropertyRemovedAsChanged() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, SAVE).build(),
            () -> this.session.getNode(DAYS).getProperty(VALUE).remove());

        final Node entry = this.session.getNode(path).getNode(this.requestId);
        assertEquals(List.of("answers/days/value"), strings(entry, CHANGED));
    }

    @Test
    void recordsAnEntityCreatedOrDeletedWithoutListingItsParts() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, "replace").build(), () -> {
            final Node two = this.session.getNode("/Requests").addNode("two", REQUEST_TYPE);
            two.addNode("answers", PART_TYPE).setProperty(VALUE, "x");
            this.session.getNode(REQUEST).remove();
        });

        final Node action = this.session.getNode(path);
        final String created = this.session.getNode("/Requests/two").getIdentifier();
        assertEquals("created", action.getNode(created).getProperty(ROLE).getString());
        assertFalse(action.getNode(created).hasProperty(ADDED));
        assertEquals("deleted", action.getNode(this.requestId).getProperty(ROLE).getString());
        assertEquals(REQUEST, action.getNode(this.requestId).getProperty("subjectPath").getString());
    }

    @Test
    void findsEveryEntityInsideABranchAddedOrRemoved() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, "import").build(), () -> {
            final Node first = this.session.getNode("/Requests").addNode("batch", "sling:Folder")
                .addNode("first", REQUEST_TYPE);
            first.addNode("wf:instances", "nt:unstructured").addNode("process", REQUEST_TYPE);
            this.session.getNode(REQUEST).addNode("followUp", REQUEST_TYPE);
        });

        final Node action = this.session.getNode(path);
        final String first = this.session.getNode("/Requests/batch/first").getIdentifier();
        final String followUp = this.session.getNode(REQUEST + "/followUp").getIdentifier();
        assertEquals("created", action.getNode(first).getProperty(ROLE).getString());
        assertEquals("created", action.getNode(followUp).getProperty(ROLE).getString());
        assertFalse(action.hasNode(this.requestId),
            "An entity holding a new entity has not changed itself: the new one is an entity of its own");
        assertEquals(2, count(action.getNodes()), "Nothing under the engine's own state is an entity of the record");

        final String removal = commit(RecordedAction.by(REQUESTER, "purge").build(),
            () -> this.session.getNode("/Requests/batch").remove());
        assertEquals("deleted", this.session.getNode(removal).getNode(first).getProperty(ROLE).getString());
    }

    @Test
    void recordsAnEntityMovedAsOneEntry() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, "archive").build(), () -> {
            this.session.getRootNode().addNode("Archive", "sling:Folder");
            this.session.move(REQUEST, "/Archive/one");
        });

        final Node entry = this.session.getNode(path).getNode(this.requestId);
        assertEquals("moved", entry.getProperty(ROLE).getString());
        assertEquals("/Archive/one", entry.getProperty("subjectPath").getString());
    }

    @Test
    void namesTheEntityAPartBelongsTo() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, "submit")
            .affecting(new RecordedEffect(DAYS, "submitted"))
            .build(), () -> this.session.getNode(DAYS).setProperty(VALUE, "5"));

        assertEquals("submitted", this.session.getNode(path).getNode(this.requestId).getProperty(ROLE).getString());
    }

    @Test
    void keepsTheFirstRoleNamedForAnEntity() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, "submit")
            .affecting(new RecordedEffect(DAYS, "submitted"))
            .affecting(new RecordedEffect(REQUEST, "flagged"))
            .build(), () -> this.session.getNode(DAYS).setProperty(VALUE, "5"));

        assertEquals("submitted", this.session.getNode(path).getNode(this.requestId).getProperty(ROLE).getString());
    }

    @Test
    void recordsTagsAndTheirDerivedValuesAsChanges() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, "submit").build(), () -> {
            final Node request = this.session.getNode(REQUEST);
            request.setProperty("tags", new String[] {"submitted"});
            request.setProperty("aggregatedTags", new String[] {"submitted"});
        });

        assertEquals(List.of("aggregatedTags", "tags"),
            strings(this.session.getNode(path).getNode(this.requestId), CHANGED));
    }

    @Test
    void recordsAnEntityTheCodeNamedThoughTheCommitLeftItAlone() throws Exception
    {
        final String other = this.session.getNode("/Requests").addNode("other", REQUEST_TYPE).getIdentifier();
        this.session.save();
        final String path = commit(RecordedAction.by(REQUESTER, "submit")
            .affecting(new RecordedEffect("/Requests/other", "consulted"))
            .build(), () -> this.session.getNode(DAYS).setProperty(VALUE, "5"));

        final Node entry = this.session.getNode(path).getNode(other);
        assertEquals("consulted", entry.getProperty(ROLE).getString());
        assertFalse(entry.hasProperty(CHANGED));
    }

    @Test
    void ignoresANameOutsideEveryEntity() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, "submit")
            .affecting(new RecordedEffect("/Requests", "ignored"))
            .affecting(new RecordedEffect("/Requests/nowhere", "ignored"))
            .build(), () -> this.session.getNode(DAYS).setProperty(VALUE, "5"));

        assertEquals(List.of(REQUEST + " updated [answers/days/value]"), entriesOf(this.session.getNode(path)));
    }

    @Test
    void namesAnEntityTheCommitRemovedFromWhereItWas() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, "purge")
            .affecting(new RecordedEffect(REQUEST, "purged"))
            .build(), () -> this.session.getNode(REQUEST).remove());

        assertEquals("purged", this.session.getNode(path).getNode(this.requestId).getProperty(ROLE).getString());
    }

    @Test
    void leavesOutAValueDerivedFromElsewhere() throws Exception
    {
        assertRecordsNothing(() -> this.session.getNode(REQUEST).setProperty("derived", "recomputed"));
    }

    @Test
    void leavesOutTheModificationStamps() throws Exception
    {
        assertRecordsNothing(() -> {
            final Node request = this.session.getNode(REQUEST);
            request.setProperty("jcr:lastModified", new GregorianCalendar());
            request.setProperty("jcr:lastModifiedBy", REQUESTER);
        });
    }

    @Test
    void leavesOutTheEnginesOwnState() throws Exception
    {
        this.session.getNode(REQUEST).addNode("wf:instances", "nt:unstructured");
        this.session.save();

        assertRecordsNothing(
            () -> this.session.getNode(REQUEST + "/wf:instances").addNode("process", "nt:unstructured"));
    }

    @Test
    void leavesOutReadMarkersAddedOrRemoved() throws Exception
    {
        assertRecordsNothing(() -> this.session.getNode(REQUEST).addNode("hist:lastSeen", "nt:unstructured"));
        assertRecordsNothing(() -> this.session.getNode(REQUEST + "/hist:lastSeen").remove());
    }

    @Test
    void leavesOutANewOrderOfParts() throws Exception
    {
        this.session.getNode(REQUEST + "/answers").addNode("reason", PART_TYPE);
        this.session.save();

        assertRecordsNothing(() -> this.session.getNode(REQUEST + "/answers").orderBefore("reason", "days"));
    }

    @Test
    void leavesOutAChangeOutsideEveryEntity() throws Exception
    {
        assertRecordsNothing(() -> this.session.getNode("/Requests").setProperty("note", "outside every entity"));
    }

    @Test
    void leavesOutAnEntityWithoutAnIdentifierToFileItBy() throws Exception
    {
        this.session.getNode("/Requests").addNode("unnamed", "test:Unnamed").addNode("part", PART_TYPE);
        this.session.save();

        assertRecordsNothing(() -> this.session.getNode("/Requests/unnamed/part").setProperty(VALUE, "x"));
    }

    @Test
    void leavesOaksHiddenNodesAlone() throws Exception
    {
        // No session can write a hidden node, so the collector walks a diff made below the sessions
        final NodeState before = this.store.getRoot();
        final NodeBuilder builder = before.builder();
        builder.getChildNode("Requests").getChildNode("one").child(":index");
        final NodeState after = builder.getNodeState();
        final NodeTypeRules rules = new NodeTypeRules(after);
        final ChangeLog added = new ChangeLog();
        final ChangeLog removed = new ChangeLog();

        EditorDiff.process(new ChangeCollector(after, rules, new EntityLocator(rules), added), before, after);
        EditorDiff.process(new ChangeCollector(before, rules, new EntityLocator(rules), removed), after, before);

        assertTrue(added.entities().isEmpty(), "A hidden node added");
        assertTrue(removed.entities().isEmpty(), "A hidden node removed");
    }

    @Test
    void recordsAnEntityReplacedAtItsOwnPathAsDeletedAndCreated() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, "replace").build(), () -> {
            this.session.getNode(REQUEST).remove();
            this.session.getNode("/Requests").addNode("one", REQUEST_TYPE);
        });

        final Node action = this.session.getNode(path);
        final String replacement = this.session.getNode(REQUEST).getIdentifier();
        assertEquals("deleted", action.getNode(this.requestId).getProperty(ROLE).getString());
        assertEquals("created", action.getNode(replacement).getProperty(ROLE).getString());
    }

    @Test
    void recordsAVersionableEntityCreatedAsItsOwnEntryAlone() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, "create").build(),
            () -> this.session.getNode("/Requests").addNode("versioned", "test:Versioned"));

        assertEquals(List.of("/Requests/versioned created"), entriesOf(this.session.getNode(path)));
    }

    @Test
    void recordsEverythingTheCauseCarried() throws Exception
    {
        final GregorianCalendar happened = new GregorianCalendar(2026, 0, 15);
        final String path = commit(RecordedAction.by(REQUESTER, "approve")
            .onBehalfOf("reviewer@example.org")
            .workflow("/Requests/one/wf:instances/process", "version-id")
            .activity("approve", "Approve the request")
            .task("/Requests/one/wf:instances/process/approve", "approved", "Looks right")
            .event("complete")
            .component("io.uhndata.iap.Sweeper")
            .partOf("/History/aa/bb/cc/parent")
            .occurredAt(happened)
            .snapshotsFollow()
            .build(), () -> this.session.getNode(DAYS).setProperty(VALUE, "5"));

        final Node action = this.session.getNode(path);
        final Map<String, String> expected = Map.of("onBehalfOf", "reviewer@example.org",
            "workflowInstance", "/Requests/one/wf:instances/process", "workflowVersion", "version-id",
            "activityId", "approve", "activityLabel", "Approve the request",
            "taskInstance", "/Requests/one/wf:instances/process/approve", "outcome", "approved",
            "outcomeNote", "Looks right", "event", "complete", "component", "io.uhndata.iap.Sweeper");
        for (final Map.Entry<String, String> property : expected.entrySet()) {
            assertEquals(property.getValue(), string(action, property.getKey()), property.getKey());
        }
        assertEquals("/History/aa/bb/cc/parent", string(action, "parentAction"));
        assertEquals(happened.getTimeInMillis(), action.getProperty("occurredAt").getDate().getTimeInMillis());
        assertFalse(action.getProperty(COMPLETE).getBoolean(), "Snapshots were promised and have not been taken");
    }

    @Test
    void recordsNothingForACommitNobodyDescribed() throws Exception
    {
        this.session.getNode(DAYS).setProperty(VALUE, "5");
        this.session.getWorkspace().getObservationManager().setUserData("somebody else's note");
        this.session.save();
        this.session.getWorkspace().getObservationManager().setUserData(null);
        this.session.getNode(DAYS).setProperty(VALUE, "6");
        this.session.save();

        assertFalse(this.session.nodeExists(RecordWriter.ROOT_PATH));
    }

    @Test
    void recordsNothingForALaterSave() throws Exception
    {
        commit(RecordedAction.by(REQUESTER, SAVE).build(), () -> this.session.getNode(DAYS).setProperty(VALUE, "5"));
        final long recorded = actions();

        this.session.getNode(DAYS).setProperty(VALUE, "6");
        this.session.save();

        assertEquals(recorded, actions(), "A later save must not be filed as part of an action that is over");
    }

    @Test
    void recordsNothingForACommitThatFails() throws Exception
    {
        assertThrows(RepositoryException.class, () -> commit(RecordedAction.by(REQUESTER, SAVE).build(),
            () -> this.session.getNode(REQUEST).addNode("broken", "hist:Action")),
            "The broken node lacks what its type insists on");
        this.session.refresh(false);
        this.session.getNode(DAYS).setProperty(VALUE, "6");
        this.session.save();

        assertEquals(0, actions(), "The description goes with the failed save, not to the next one");
    }

    @Test
    void filesActionsWhereThePrefixTreeSays() throws Exception
    {
        final Iterator<String> names = List.of("abcdefgh-1", "abcdefgh-2", "abcdefgh-2").iterator();
        this.recorder = new HistoryRecorderImpl(names::next);
        final String first = commit(RecordedAction.by("a", SAVE).build(),
            () -> this.session.getNode(DAYS).setProperty(VALUE, "5"));
        final String second = commit(RecordedAction.by("b", SAVE).build(),
            () -> this.session.getNode(DAYS).setProperty(VALUE, "6"));

        assertEquals("/History/ab/cd/ef/abcdefgh-1", first);
        assertEquals("/History/ab/cd/ef/abcdefgh-2", second);
        assertThrows(ConstraintViolationException.class, () -> commit(RecordedAction.by("c", SAVE).build(),
            () -> this.session.getNode(DAYS).setProperty(VALUE, "7")),
            "Two actions under one name would be one record of two events, and no race to try again");
    }

    @Test
    void attachesTheSnapshotsAndMarksTheActionComplete() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, "submit").snapshotsFollow().build(),
            () -> this.session.getNode(DAYS).setProperty(VALUE, "5"));

        this.recorder.completeSnapshots(this.session, path, Map.of(this.requestId, "version-id"));

        assertEquals("version-id", string(this.session.getNode(path).getNode(this.requestId), "snapshot"));
        assertTrue(this.session.getNode(path).getProperty(COMPLETE).getBoolean());
        assertFalse(this.session.hasPendingChanges());
    }

    @Test
    void refusesASnapshotOfAResourceTheActionDidNotAffect() throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, "submit").snapshotsFollow().build(),
            () -> this.session.getNode(DAYS).setProperty(VALUE, "5"));

        assertThrows(PathNotFoundException.class,
            () -> this.recorder.completeSnapshots(this.session, path, Map.of("a-stranger", "version-id")));
    }

    private String commit(final RecordedAction action, final Edit edit) throws Exception
    {
        edit.apply();
        return this.recorder.commit(this.session, action);
    }

    private void assertRecordsNothing(final Edit edit) throws Exception
    {
        final String path = commit(RecordedAction.by(REQUESTER, SAVE).build(), edit);
        assertEquals(List.of(), entriesOf(this.session.getNode(path)));
    }

    private long actions() throws RepositoryException
    {
        return count(this.session.getWorkspace().getQueryManager()
            .createQuery("SELECT * FROM [hist:Action]", "JCR-SQL2").execute().getNodes());
    }

    private void register(final Reader cnd) throws Exception
    {
        try (cnd) {
            CndImporter.registerNodeTypes(cnd, this.session);
        }
    }

    private static Reader cnd(final String resource)
    {
        return new InputStreamReader(Objects.requireNonNull(
            HistoryRecordingTest.class.getClassLoader().getResourceAsStream(resource), resource),
            StandardCharsets.UTF_8);
    }

    private static List<NodeType> allTypesOf(final Node node) throws RepositoryException
    {
        final List<NodeType> types = new ArrayList<>(List.of(node.getPrimaryNodeType()));
        types.addAll(Arrays.asList(node.getPrimaryNodeType().getSupertypes()));
        return types;
    }

    private static List<String> strings(final Node node, final String property) throws RepositoryException
    {
        final List<String> values = new ArrayList<>();
        for (final Value value : node.getProperty(property).getValues()) {
            values.add(value.getString());
        }
        return values;
    }

    private static List<String> entriesOf(final Node action) throws RepositoryException
    {
        final List<String> entries = new ArrayList<>();
        for (final NodeIterator children = action.getNodes(); children.hasNext();) {
            final Node entry = children.nextNode();
            entries.add(string(entry, "subjectPath") + " " + string(entry, ROLE)
                + (entry.hasProperty(CHANGED) ? " " + strings(entry, CHANGED) : ""));
        }
        return entries;
    }

    private static String string(final Node node, final String property) throws RepositoryException
    {
        return node.getProperty(property).getString();
    }

    private static long count(final NodeIterator nodes)
    {
        long found = 0;
        while (nodes.hasNext()) {
            nodes.nextNode();
            found++;
        }
        return found;
    }

    /**
     * A change to make while a description is on the session.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    private interface Edit
    {
        /**
         * Makes the change, without saving.
         *
         * @throws RepositoryException if the change cannot be made
         */
        void apply() throws RepositoryException;
    }
}
