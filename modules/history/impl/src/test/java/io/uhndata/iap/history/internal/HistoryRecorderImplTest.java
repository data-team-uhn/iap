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

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.history.api.HistoryRecorder;
import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.api.RecordedEffect;
import io.uhndata.iap.history.models.Action;
import io.uhndata.iap.history.models.Entry;
import io.uhndata.iap.utils.PrefixTree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HistoryRecorderImpl}, against a real repository, since a mock resolver cannot show that the
 * record shares the caller's transaction.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class HistoryRecorderImplTest
{
    private static final List<String> OPTIONAL_PROPERTIES = List.of("onBehalfOf", "workflowInstance",
        "workflowVersion", "activityId", "activityLabel", "taskInstance", "outcome", "outcomeNote", "event",
        "component", "parentAction", "occurredAt");

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private Session session;

    private TestResolverFactory resolverFactory;

    private HistoryRecorder recorder;

    @BeforeEach
    void setUp() throws Exception
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        NodeTypeDefinitionScanner.get().register(this.session,
            List.of("SLING-INF/nodetypes/content.cnd", "SLING-INF/nodetypes/history.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.session.getRootNode().addNode("History", "hist:Log");
        this.session.getRootNode().addNode("content", "nt:unstructured");
        this.session.save();
        this.context.addModelsForClasses(Content.class, Action.class, Entry.class);
        this.resolverFactory = TestResolverFactory.serving(this.context.resourceResolver());
        this.recorder = new HistoryRecorderImpl(this.resolverFactory);
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
        assertEquals("reviewer1", action.getProperty("createdBy").getString());
        assertEquals("submit", action.getProperty("operation").getString());
        assertEquals("submissionFiled", action.getProperty("event").getString());
        assertEquals(PrefixTree.pathFor("/History", action.getName()), path,
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
            .by("admin", "activate")
            .affecting(RecordedEffect.on(old, "retired", "tags"))
            .affecting(RecordedEffect.on(current, "activated", "tags"))
            .build());
        this.session.save();

        final Node action = this.session.getNode(path);
        assertEquals("retired", action.getNode(old.getIdentifier()).getProperty("role").getString());
        assertEquals("activated", action.getNode(current.getIdentifier()).getProperty("role").getString());
        assertEquals("/content/oldVersion",
            action.getNode(old.getIdentifier()).getProperty("subjectPath").getString());
        assertEquals("tags",
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
    void recordedActionsReadBackThroughTheModel() throws Exception
    {
        final Node subject = this.node("study");
        final Calendar occurredAt = new GregorianCalendar(2026, Calendar.MARCH, 2, 9, 30);
        occurredAt.setTimeZone(TimeZone.getTimeZone("UTC"));
        final String path = this.recorder.record(this.session, RecordedAction
            .by("reviewer1", "decide")
            .onBehalfOf("chair1")
            .workflow("/Submissions/aStudy/wf:instances/review", "22222222-2222-2222-2222-222222222222")
            .activity("Activity_review", "Review the submission")
            .task("/Submissions/aStudy/wf:instances/review/approveStudy", "rejected", "The budget letter is missing")
            .event("taskCompleted")
            .component("io.uhndata.iap.test")
            .partOf("/History/pa/re/nt/parent")
            .occurredAt(occurredAt)
            .affecting(RecordedEffect.on(subject, "rejected", "tags"))
            .build());
        this.session.save();

        final Action action = this.context.resourceResolver().getResource(path).adaptTo(Action.class);
        assertEquals("reviewer1", action.getRequestedBy());
        assertEquals("decide", action.getOperation());
        assertEquals("chair1", action.getOnBehalfOf());
        assertEquals("/Submissions/aStudy/wf:instances/review", action.getWorkflowInstance());
        assertEquals("22222222-2222-2222-2222-222222222222", action.getWorkflowVersion());
        assertEquals("Activity_review", action.getActivityId());
        assertEquals("Review the submission", action.getActivityLabel());
        assertEquals("/Submissions/aStudy/wf:instances/review/approveStudy", action.getTaskInstance());
        assertEquals("rejected", action.getOutcome());
        assertEquals("The budget letter is missing", action.getOutcomeNote());
        assertEquals("taskCompleted", action.getEvent());
        assertEquals("io.uhndata.iap.test", action.getComponent());
        assertEquals("/History/pa/re/nt/parent", action.getParentAction());
        assertEquals(occurredAt.getTimeInMillis(), action.getOccurredAt().getTimeInMillis());

        final Entry entry = action.getEntries().get(0);
        assertEquals(subject.getIdentifier(), entry.getSubject());
        assertEquals("/content/study", entry.getSubjectPath());
        assertEquals("nt:unstructured", entry.getSubjectType());
        assertEquals("rejected", entry.getRole());
        assertEquals(List.of("tags"), entry.getChanges());
    }

    @Test
    void writesOnlyWhatItWasTold() throws Exception
    {
        final String path = this.recorder.record(this.session, RecordedAction.by("reviewer1", "submit").build());
        this.session.save();

        final Node action = this.session.getNode(path);
        for (final String property : OPTIONAL_PROPERTIES) {
            assertFalse(action.hasProperty(property), property);
        }
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
        // Two different actions, filed in the same bucket: same leading characters, different names
        final Iterator<String> names = List.of("abcdef0", "abcdef1").iterator();
        final HistoryRecorder pinned = new HistoryRecorderImpl(this.resolverFactory, names::next);

        final String first = pinned.record(this.session, RecordedAction.by("a", "submit").build());
        this.session.save();
        final String second = pinned.record(this.session, RecordedAction.by("b", "submit").build());
        this.session.save();

        assertEquals(this.session.getNode(first).getParent().getPath(),
            this.session.getNode(second).getParent().getPath());
        assertEquals(1, this.resolverFactory.serviceLogins(),
            "Only the first action needed a bucket made, so only it needed the store's own session");
    }

    @Test
    void createsTheBucketWhenNothingHasLandedInItYet() throws Exception
    {
        final String path = this.recorder.record(this.session, RecordedAction.by("a", "submit").build());
        this.session.save();

        assertTrue(this.session.nodeExists(path));
        assertEquals("hist:Log", this.session.getNode(path).getParent().getPrimaryNodeType().getName());
    }

    @Test
    void keepsTheCallersPendingChangesWhenItCreatesABucket() throws Exception
    {
        final Session store = this.session.getRepository()
            .login(new SimpleCredentials("admin", "admin".toCharArray()));
        final ResourceResolver storeResolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(storeResolver.adaptTo(Session.class)).thenReturn(store);
        final HistoryRecorder separate = new HistoryRecorderImpl(TestResolverFactory.serving(storeResolver));
        this.session.getNode("/content").setProperty("title", "Not saved yet");

        final String path = separate.record(this.session, RecordedAction.by("a", "submit").build());
        assertTrue(this.session.getNode("/content").hasProperty("title"),
            "Seeing the new bucket must not have discarded the caller's change");
        this.session.refresh(false);

        assertFalse(this.session.getNode("/content").hasProperty("title"),
            "Creating the bucket must not have saved the caller's change");
        assertFalse(this.session.nodeExists(path));
        assertTrue(this.session.nodeExists(path.substring(0, path.lastIndexOf('/'))),
            "The bucket was saved by the store's own session, so it outlives the caller's transaction");
        store.logout();
    }

    @Test
    void reportsPlainlyWhenItCannotReachItsOwnServiceUser() throws Exception
    {
        final HistoryRecorder refused = new HistoryRecorderImpl(TestResolverFactory.refusingLogin());

        final RepositoryException thrown = assertThrows(RepositoryException.class,
            () -> refused.record(this.session, RecordedAction.by("reviewer1", "submit").build()));
        assertTrue(thrown.getMessage().contains("service user"),
            "The reason has to name the service user, or this reads as the repository being broken");
    }

    @Test
    void reportsPlainlyWhenItsServiceResolverHasNoSession() throws Exception
    {
        final HistoryRecorder sessionless =
            new HistoryRecorderImpl(TestResolverFactory.serving(Mockito.mock(ResourceResolver.class)));

        final RepositoryException thrown = assertThrows(RepositoryException.class,
            () -> sessionless.record(this.session, RecordedAction.by("reviewer1", "submit").build()));
        assertTrue(thrown.getMessage().contains("session"));
    }

    private Node node(final String name) throws Exception
    {
        final Node node = this.session.getNode("/content").addNode(name, "nt:unstructured");
        node.addMixin("mix:referenceable");
        this.session.save();
        return node;
    }
}
