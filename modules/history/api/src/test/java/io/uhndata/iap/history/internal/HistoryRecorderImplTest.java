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

import java.util.List;
import java.util.Map;

import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.history.api.HistoryRecorder;
import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.api.RecordedEffect;
import io.uhndata.iap.utils.PrefixTree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HistoryRecorderImpl}, against a real repository: what this class is for is sharing the
 * caller's transaction, and a mock resolver cannot show that.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class HistoryRecorderImplTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private Session session;

    private HistoryRecorder recorder;

    @BeforeEach
    void setUp() throws Exception
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        NodeTypeDefinitionScanner.get().register(this.session,
            List.of("SLING-INF/nodetypes/content.cnd", "SLING-INF/nodetypes/history.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.session.getRootNode().addNode("History", HistoryRecorderImpl.BUCKET_TYPE);
        this.session.getRootNode().addNode("content", "nt:unstructured");
        this.session.save();
        final HistoryRecorderImpl impl = new HistoryRecorderImpl();
        inject(impl, "resolverFactory", new TestResolverFactory(this.context.resourceResolver()));
        this.recorder = impl;
    }

    @Test
    void recordsTheCauseAndFilesItInThePrefixTree() throws Exception
    {
        final String path = this.recorder.record(this.session, RecordedAction
            .by("reviewer1", "submit")
            .event("submissionFiled")
            .build());
        this.session.save();

        final Node action = this.session.getNode(path);
        assertEquals("reviewer1", action.getProperty("actor").getString());
        assertEquals("submit", action.getProperty("operation").getString());
        assertEquals("submissionFiled", action.getProperty("event").getString());
        assertEquals(PrefixTree.pathFor(HistoryRecorderImpl.ROOT, action.getName()), path,
            "The action must be filed where the prefix tree says it is, or nothing can find it by name");
        assertFalse(action.getProperty("complete").getBoolean(),
            "Nothing has snapshotted anything yet, so the action cannot claim to be finished");
    }

    @Test
    void leavesTheRecordPendingSoItCommitsWithTheChange() throws Exception
    {
        final String path = this.recorder.record(this.session, RecordedAction.by("reviewer1", "submit").build());

        assertTrue(this.session.hasPendingChanges(),
            "The record must be left pending: committing it here would record a change that may yet be abandoned");
        this.session.refresh(false);
        assertFalse(this.session.nodeExists(path),
            "Discarding the caller's transaction must discard the record of it too");
    }

    @Test
    void recordsWhatWasDoneToEachAffectedResourceAndThePartItPlayed() throws Exception
    {
        final Node old = this.node("oldVersion");
        final Node current = this.node("newVersion");
        final String path = this.recorder.record(this.session, RecordedAction
            .by("admin", "activateVersion")
            .affecting(RecordedEffect.on(old, "retired", "active"))
            .affecting(RecordedEffect.on(current, "activated", "active"))
            .build());
        this.session.save();

        final Node action = this.session.getNode(path);
        assertEquals("retired", action.getNode(old.getIdentifier()).getProperty("role").getString());
        assertEquals("activated", action.getNode(current.getIdentifier()).getProperty("role").getString());
        assertEquals("/content/oldVersion",
            action.getNode(old.getIdentifier()).getProperty("subjectPath").getString());
        assertEquals("active",
            action.getNode(old.getIdentifier()).getProperty("changes").getValues()[0].getString());
    }

    @Test
    void recordsNoChangeListWhenNothingIsNamed() throws Exception
    {
        final Node subject = this.node("study");
        final String path = this.recorder.record(this.session, RecordedAction
            .by("reviewer1", "acknowledge")
            .affecting(RecordedEffect.on(subject, "seen"))
            .build());
        this.session.save();

        assertFalse(this.session.getNode(path).getNode(subject.getIdentifier()).hasProperty("changes"));
    }

    @Test
    void refusesToNameTheSameResourceTwiceInOneAction() throws Exception
    {
        final Node subject = this.node("study");
        final RecordedAction action = RecordedAction
            .by("reviewer1", "submit")
            .affecting(RecordedEffect.on(subject, "submitted"))
            .affecting(RecordedEffect.on(subject, "flagged"))
            .build();

        assertThrows(ItemExistsException.class, () -> this.recorder.record(this.session, action),
            "One action did one thing to one resource; two entries for it would be two answers to one question");
    }

    @Test
    void carriesEverythingAWorkflowKnowsAboutTheDecision() throws Exception
    {
        final String path = this.recorder.record(this.session, RecordedAction
            .by("reviewer1", "decide")
            .onBehalfOf("chair1")
            .workflow("instance-id", "version-id")
            .activity("Activity_review", "Review the submission")
            .task("task-id", "rejected", "The budget letter is missing")
            .event("taskCompleted")
            .component("io.uhndata.iap.test")
            .partOf("parent-id")
            .build());
        this.session.save();

        final Node action = this.session.getNode(path);
        assertEquals("chair1", action.getProperty("onBehalfOf").getString());
        assertEquals("instance-id", action.getProperty("workflowInstance").getString());
        assertEquals("version-id", action.getProperty("workflowVersion").getString());
        assertEquals("Activity_review", action.getProperty("activityId").getString());
        assertEquals("Review the submission", action.getProperty("activityLabel").getString());
        assertEquals("task-id", action.getProperty("taskInstance").getString());
        assertEquals("rejected", action.getProperty("outcome").getString());
        assertEquals("The budget letter is missing", action.getProperty("outcomeNote").getString());
        assertEquals("io.uhndata.iap.test", action.getProperty("component").getString());
        assertEquals("parent-id", action.getProperty("parentAction").getString());
    }

    @Test
    void writesOnlyWhatItWasTold() throws Exception
    {
        final String path = this.recorder.record(this.session, RecordedAction.by("reviewer1", "submit").build());
        this.session.save();

        final Node action = this.session.getNode(path);
        assertFalse(action.hasProperty("onBehalfOf"));
        assertFalse(action.hasProperty("workflowInstance"));
        assertFalse(action.hasProperty("outcomeNote"));
    }

    @Test
    void attachesTheSnapshotsAndMarksTheActionFinished() throws Exception
    {
        final Node subject = this.node("study");
        final String path = this.recorder.record(this.session, RecordedAction
            .by("reviewer1", "submit")
            .affecting(RecordedEffect.on(subject, "submitted"))
            .build());
        this.session.save();

        this.recorder.completeSnapshots(this.session, path, Map.of(subject.getIdentifier(), "version-id"));

        final Node action = this.session.getNode(path);
        assertEquals("version-id", action.getNode(subject.getIdentifier()).getProperty("snapshot").getString());
        assertTrue(action.getProperty("complete").getBoolean());
        assertFalse(this.session.hasPendingChanges(), "Completing happens after the caller has committed, so it saves");
    }

    @Test
    void marksAnActionFinishedEvenWhenItSnapshottedNothing() throws Exception
    {
        final String path = this.recorder.record(this.session, RecordedAction.by("reviewer1", "submit").build());
        this.session.save();

        this.recorder.completeSnapshots(this.session, path, Map.of());

        assertTrue(this.session.getNode(path).getProperty("complete").getBoolean(),
            "An action that wanted no snapshot is still finished, which is what tells a reader the absence is normal");
    }

    @Test
    void refusesASnapshotForAResourceTheActionNeverTouched() throws Exception
    {
        final String path = this.recorder.record(this.session, RecordedAction.by("reviewer1", "submit").build());
        this.session.save();

        assertThrows(PathNotFoundException.class,
            () -> this.recorder.completeSnapshots(this.session, path, Map.of("a-stranger", "version-id")));
    }

    @Test
    void reusesTheBucketOnceItExistsRatherThanOpeningItsOwnSessionAgain() throws Exception
    {
        // Pinning the name is the only way to ask for the same bucket twice, since it is random by design
        final HistoryRecorderImpl impl = new HistoryRecorderImpl()
        {
            private int recorded;

            @Override
            String newActionName()
            {
                // Two different actions, filed in the same bucket: same leading characters, different names
                return "abcdef" + this.recorded++;
            }
        };
        inject(impl, "resolverFactory", new TestResolverFactory(this.context.resourceResolver()));

        final String first = impl.record(this.session, RecordedAction.by("a", "submit").build());
        this.session.save();
        final String second = impl.record(this.session, RecordedAction.by("b", "submit").build());
        this.session.save();

        assertEquals(this.session.getNode(first).getParent().getPath(),
            this.session.getNode(second).getParent().getPath(),
            "Two actions whose names share their leading characters belong in one bucket");
        assertTrue(this.session.nodeExists(first));
        assertTrue(this.session.nodeExists(second));
    }

    @Test
    void createsTheBucketWhenNothingHasLandedInItYet() throws Exception
    {
        final String path = this.recorder.record(this.session, RecordedAction.by("a", "submit").build());
        this.session.save();

        assertTrue(this.session.nodeExists(path));
        assertEquals(HistoryRecorderImpl.BUCKET_TYPE,
            this.session.getNode(path).getParent().getPrimaryNodeType().getName());
    }

    @Test
    void reportsPlainlyWhenItCannotReachItsOwnServiceUser() throws Exception
    {
        // The buckets do not exist yet, so recording has to open the store's own session -- and there is none
        final HistoryRecorderImpl impl = new HistoryRecorderImpl();
        inject(impl, "resolverFactory", new TestResolverFactory(null));

        final RepositoryException thrown = assertThrows(RepositoryException.class,
            () -> impl.record(this.session, RecordedAction.by("reviewer1", "submit").build()));
        assertTrue(thrown.getMessage().contains("service user"),
            "The reason has to name the service user, or this reads as the repository being broken");
    }

    @Test
    void reportsPlainlyWhenItsServiceResolverHasNoSession() throws Exception
    {
        final HistoryRecorderImpl impl = new HistoryRecorderImpl();
        inject(impl, "resolverFactory", new TestResolverFactory(Mockito.mock(ResourceResolver.class)));

        final RepositoryException thrown = assertThrows(RepositoryException.class,
            () -> impl.record(this.session, RecordedAction.by("reviewer1", "submit").build()));
        assertTrue(thrown.getMessage().contains("session"));
    }

    private static void inject(final Object target, final String field, final Object value) throws Exception
    {
        // Declared on HistoryRecorderImpl itself, which is not always the object's own class: one test overrides the
        // name generator with an anonymous subclass
        final java.lang.reflect.Field declared = HistoryRecorderImpl.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    private Node node(final String name) throws Exception
    {
        final Node node = this.session.getNode("/content").addNode(name, "nt:unstructured");
        node.addMixin("mix:referenceable");
        this.session.save();
        return node;
    }
}
