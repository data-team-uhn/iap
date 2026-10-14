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

import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.history.api.HistoryRecorder;
import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.api.RecordedEffect;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * What one event delivery did, gathered while it runs and written to history just before it commits: one action
 * for the event, and one more for each event a workflow sent along the way, recorded as part of it.
 *
 * <p>Written last because a sent event's action must name the action it is part of, which only exists once
 * written, and because what was created is only known at the end.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ActionRecord
{
    private final String event;

    private final RecordedAction.Builder action;

    private final List<ActionRecord> sent = new ArrayList<>();

    private ActionRecord(final Actor actor, final WorkflowEvent event)
    {
        this.event = event.getName();
        this.action = RecordedAction.by(actor.id(), event.getName())
            .onBehalfOf(actor.onBehalfOf())
            .event(event.getName());
    }

    /**
     * Starts the record of an event that came in.
     *
     * @param actor who sent it, and for whom
     * @param event the event
     * @return an empty record
     */
    static ActionRecord of(final Actor actor, final WorkflowEvent event)
    {
        return new ActionRecord(actor, event);
    }

    /**
     * Starts the record of an event a workflow sent while carrying this one out.
     *
     * @param actor who the sending execution acts for
     * @param event the sent event
     * @return an empty record, written as part of this one
     */
    ActionRecord send(final Actor actor, final WorkflowEvent event)
    {
        final ActionRecord child = new ActionRecord(actor, event);
        this.sent.add(child);
        return child;
    }

    /**
     * Records which workflow carried the action out.
     *
     * @param instance the instance that was moved, or {@code null} for a system workflow, which leaves none
     * @param version the definition version in force, or {@code null} if it cannot be found
     */
    void workflow(final WorkflowInstance instance, final WorkflowVersion version)
    {
        this.action.workflow(instance == null ? null : instance.getIdentifier(),
            version == null ? null : version.getIdentifier());
    }

    /**
     * Records the task the action decided.
     *
     * @param task the task completed
     * @param definition the activity it was an instance of
     * @param outcome the outcome it was completed with, or {@code null} if none was given
     */
    void task(final TaskInstance task, final Activity definition, final String outcome)
    {
        workflow(task.getWorkflowInstance(), definition.getWorkflowVersion());
        this.action.activity(definition.getElementId(), definition.getLabel())
            .task(task.getIdentifier(), outcome, null);
    }

    /**
     * Records that the action affected a resource, if the resource can be named in history.
     *
     * @param resource the affected resource
     * @param role the part it played
     * @throws WorkflowFailedException when the resource cannot be read
     */
    void affecting(final Resource resource, final String role) throws WorkflowFailedException
    {
        final Node node = resource.adaptTo(Node.class);
        try {
            // History names an entry after the resource's identifier, which only a referenceable node has free
            // of slashes. Homepages are not, and are only ever where something was created
            if (node != null && !node.getIdentifier().contains("/")) {
                this.action.affecting(RecordedEffect.on(node, role));
            }
        } catch (final RepositoryException e) {
            throw new WorkflowFailedException("Could not record what happened to " + resource.getPath(), e);
        }
    }

    /**
     * Writes this action, then those of the events sent while carrying it out, pending in the session.
     *
     * @param session the engine's session, still uncommitted
     * @param recorder where history is written
     * @throws WorkflowFailedException when the record cannot be written, which must stop the event happening
     */
    void write(final Session session, final HistoryRecorder recorder) throws WorkflowFailedException
    {
        write(session, recorder, null);
    }

    /**
     * Writes this action as part of another, then those of the events sent while carrying it out.
     *
     * @param session the engine's session, still uncommitted
     * @param recorder where history is written
     * @param parent the path of the action this one is part of, or {@code null}
     * @throws WorkflowFailedException when the record cannot be written
     */
    private void write(final Session session, final HistoryRecorder recorder, final String parent)
        throws WorkflowFailedException
    {
        final String path;
        try {
            path = recorder.record(session, this.action.partOf(parent).build());
        } catch (final RepositoryException e) {
            throw new WorkflowFailedException("Could not record the " + this.event + " event in history", e);
        }
        for (final ActionRecord child : this.sent) {
            child.write(session, recorder, path);
        }
    }
}
