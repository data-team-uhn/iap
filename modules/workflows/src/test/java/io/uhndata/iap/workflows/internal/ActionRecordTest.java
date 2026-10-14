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
package io.uhndata.iap.workflows.internal;

import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.history.api.HistoryRecorder;
import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.api.RecordedEffect;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ActionRecord}: what one event delivery writes to history, and how the events it sent hang
 * off it.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ActionRecordTest
{
    private static final WorkflowEvent SAVE = new WorkflowEvent("save", Map.of());

    private final Session session = Mockito.mock(Session.class);

    private final EngineFixture.History history = new EngineFixture.History();

    @Test
    void recordsWhoSentTheEventAndForWhom() throws Exception
    {
        ActionRecord.of(new Actor("admin", "alice"), SAVE).write(this.session, this.history);

        final RecordedAction action = this.history.actions().get(0);
        assertEquals("admin", action.getActor());
        assertEquals("alice", action.getOnBehalfOf());
        assertEquals("save", action.getOperation());
        assertEquals("save", action.getEvent());
    }

    @Test
    void recordsSentEventsAsPartOfTheOneThatSentThem() throws Exception
    {
        final ActionRecord record = ActionRecord.of(Actor.of("alice"), SAVE);
        final ActionRecord sent = record.send(Actor.of("alice"), new WorkflowEvent("validate", Map.of()));
        sent.send(Actor.of("alice"), new WorkflowEvent("notify", Map.of()));

        record.write(this.session, this.history);

        assertEquals(List.of("save", "validate", "notify"),
            this.history.actions().stream().map(RecordedAction::getEvent).toList());
        assertNull(this.history.actions().get(0).getParentAction());
        assertEquals("action1", this.history.actions().get(1).getParentAction());
        assertEquals("action2", this.history.actions().get(2).getParentAction());
    }

    @Test
    void recordsTheTaskDecidedAndTheWorkflowItBelongsTo() throws Exception
    {
        // Both are parts of their host, so their identifiers are the host's followed by a path
        final Resource instance = resource(node("cafebabe-0000-0000-0000-000000000000/wf:instances/review"));
        final WorkflowVersion version = Mockito.mock(WorkflowVersion.class);
        Mockito.when(version.getIdentifier()).thenReturn("version-id");
        final Resource task =
            resource(node("cafebabe-0000-0000-0000-000000000000/wf:instances/review/approveRequest"));
        Mockito.when(task.getParent()).thenReturn(instance);
        final Activity definition = Mockito.mock(Activity.class);
        Mockito.when(definition.getWorkflowVersion()).thenReturn(version);
        Mockito.when(definition.getElementId()).thenReturn("approveRequest");
        Mockito.when(definition.getLabel()).thenReturn("Approve the request");
        final ActionRecord record = ActionRecord.of(Actor.of("alice"), SAVE);

        record.task(task, definition, "approved");
        record.write(this.session, this.history);

        final RecordedAction action = this.history.actions().get(0);
        assertEquals("cafebabe-0000-0000-0000-000000000000/wf:instances/review", action.getWorkflowInstance());
        assertEquals("version-id", action.getWorkflowVersion());
        assertEquals("approveRequest", action.getActivityId());
        assertEquals("Approve the request", action.getActivityLabel());
        assertEquals("cafebabe-0000-0000-0000-000000000000/wf:instances/review/approveRequest",
            action.getTaskInstance());
        assertEquals("approved", action.getOutcome());
    }

    @Test
    void namesNoWorkflowItCannotFind() throws Exception
    {
        final ActionRecord record = ActionRecord.of(Actor.of("alice"), SAVE);

        record.workflow(null, null);
        record.write(this.session, this.history);

        assertNull(this.history.actions().get(0).getWorkflowInstance());
        assertNull(this.history.actions().get(0).getWorkflowVersion());
    }

    @Test
    void recordsWhatHappenedToAReferenceableResource() throws Exception
    {
        final ActionRecord record = ActionRecord.of(Actor.of("alice"), SAVE);

        record.affecting(resource(node("1f0e8c2a-7d3b-4c51-9a8e-2b6d4f0c9e17")), "target");
        record.write(this.session, this.history);

        final RecordedEffect effect = this.history.actions().get(0).getEffects().get(0);
        assertEquals("1f0e8c2a-7d3b-4c51-9a8e-2b6d4f0c9e17", effect.subject());
        assertEquals("target", effect.role());
    }

    @Test
    void leavesOutAResourceHistoryCannotNameAnEntryAfter() throws Exception
    {
        // A node that is not referenceable has an identifier built from a path, and an entry cannot be named that
        final ActionRecord record = ActionRecord.of(Actor.of("alice"), SAVE);

        record.affecting(resource(node("cafebabe-0000-0000-0000-000000000000/Workflows")), "target");
        record.affecting(resource(null), "created");
        record.write(this.session, this.history);

        assertTrue(this.history.actions().get(0).getEffects().isEmpty());
    }

    @Test
    void failsWhenAnAffectedResourceCannotBeRead() throws Exception
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getIdentifier()).thenThrow(new RepositoryException("the node is gone"));
        final ActionRecord record = ActionRecord.of(Actor.of("alice"), SAVE);

        assertThrows(WorkflowFailedException.class, () -> record.affecting(resource(node), "target"));
    }

    @Test
    void failsWhenTheInstanceMovedCannotBeRead() throws Exception
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getIdentifier()).thenThrow(new RepositoryException("the node is gone"));
        final ActionRecord record = ActionRecord.of(Actor.of("alice"), SAVE);

        assertThrows(WorkflowFailedException.class, () -> record.workflow(resource(node), null));
    }

    @Test
    void failsWhenTheRecordCannotBeWritten() throws Exception
    {
        final HistoryRecorder broken = Mockito.mock(HistoryRecorder.class);
        Mockito.when(broken.record(Mockito.any(), Mockito.any()))
            .thenThrow(new RepositoryException("the history store is full"));
        final ActionRecord record = ActionRecord.of(Actor.of("alice"), SAVE);

        final WorkflowFailedException failure =
            assertThrows(WorkflowFailedException.class, () -> record.write(this.session, broken));
        assertTrue(failure.getMessage().contains("save"));
    }

    /**
     * A JCR node with the given identifier.
     *
     * @param identifier what the repository identifies it by
     * @return a stub node
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static Node node(final String identifier) throws RepositoryException
    {
        final NodeType type = Mockito.mock(NodeType.class);
        Mockito.when(type.getName()).thenReturn("sub:Submission");
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getIdentifier()).thenReturn(identifier);
        Mockito.when(node.getPath()).thenReturn("/Submissions/proposal-42");
        Mockito.when(node.getPrimaryNodeType()).thenReturn(type);
        return node;
    }

    /**
     * A resource backed by the given node.
     *
     * @param node the node it adapts to, or {@code null} for one with no node behind it
     * @return a stub resource
     */
    private static Resource resource(final Node node)
    {
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.adaptTo(Node.class)).thenReturn(node);
        Mockito.when(resource.getPath()).thenReturn("/Submissions/proposal-42");
        return resource;
    }
}
