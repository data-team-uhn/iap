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
package io.uhndata.iap.history.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;

/**
 * One thing that was asked for, and everything that followed from it.
 *
 * <p>
 * An action is one event delivery, not one step of the workflow that carried it out: the engine runs a workflow to
 * quiescence and commits the lot at once, so a step is not separately committed and cannot be a fact on its own. That
 * also keeps the record steady when a definition is reworked — retiring one workflow version and activating another is
 * one action whether the definition spends one service task on it or two.
 * </p>
 *
 * <p>
 * The cause is recorded here, once. What the action did to each resource it touched is a {@link Entry} child, because
 * one action usually does different things to different resources.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Action.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Action extends Content
{
    /** The Sling resource type of a recorded action. */
    public static final String RESOURCE_TYPE = "hist/Action";

    /** Who is answerable. */
    @ValueMapValue
    private String actor;

    /** Who they acted for, if not themselves. */
    @ValueMapValue
    private String onBehalfOf;

    /** What was asked for. */
    @ValueMapValue
    private String operation;

    /** The instance that was moved. */
    @ValueMapValue
    private String workflowInstance;

    /** The definition version that was in force. */
    @ValueMapValue
    private String workflowVersion;

    /** The definition node being performed. */
    @ValueMapValue
    private String activityId;

    /** Its label, as the definition read at the time. */
    @ValueMapValue
    private String activityLabel;

    /** The task somebody completed to cause this. */
    @ValueMapValue
    private String taskInstance;

    /** The decision they took. */
    @ValueMapValue
    private String outcome;

    /** What they said about it. */
    @ValueMapValue
    private String outcomeNote;

    /** The name of the event delivered. */
    @ValueMapValue
    private String event;

    /** The component responsible, when no workflow was. */
    @ValueMapValue
    private String component;

    /** The action this was part of. */
    @ValueMapValue
    private String parentAction;

    /** Whether every snapshot this action intended was taken. */
    @ValueMapValue
    private Boolean complete;

    /**
     * Who is answerable for this action, as a canonical user id.
     *
     * <p>
     * Deliberately not {@link #getCreatedBy()}, which is the service user that wrote the record: the engine writes as
     * itself, and the person acting often holds no more than read access on what they changed.
     * </p>
     *
     * @return a user id, empty only in a malformed record
     */
    @NotNull
    public String getActor()
    {
        return this.actor == null ? "" : this.actor;
    }

    /**
     * Whom the actor was acting for — a delegate arrangement, or the system on somebody's behalf.
     *
     * @return a user id, or {@code null} when the actor acted for themselves
     */
    @Nullable
    public String getOnBehalfOf()
    {
        return this.onBehalfOf;
    }

    /**
     * What was asked for, in the platform's own vocabulary: {@code submit}, {@code activateVersion}. The one thing a
     * reader can group actions by without reading any workflow definition.
     *
     * @return an operation name, empty only in a malformed record
     */
    @NotNull
    public String getOperation()
    {
        return this.operation == null ? "" : this.operation;
    }

    /**
     * The identifier of the workflow instance this action moved.
     *
     * @return an identifier, or {@code null} for an action no instance was involved in
     */
    @Nullable
    public String getWorkflowInstance()
    {
        return this.workflowInstance;
    }

    /**
     * The identifier of the exact workflow version that decided what this action meant.
     *
     * @return an identifier, or {@code null} when no workflow was involved
     */
    @Nullable
    public String getWorkflowVersion()
    {
        return this.workflowVersion;
    }

    /**
     * The {@code elementId} of the definition node being performed.
     *
     * @return an element id, or {@code null} when no workflow was involved
     */
    @Nullable
    public String getActivityId()
    {
        return this.activityId;
    }

    /**
     * That node's label as the definition read at the time. Copied rather than looked up, for the same reason a task
     * copies its own: the reader may not be able to read the definition, and the definition may since have been
     * reworded.
     *
     * @return a label, or {@code null} when none was recorded
     */
    @Nullable
    public String getActivityLabel()
    {
        return this.activityLabel;
    }

    /**
     * The identifier of the task whose completion caused this action.
     *
     * @return an identifier, or {@code null} when no task was completed
     */
    @Nullable
    public String getTaskInstance()
    {
        return this.taskInstance;
    }

    /**
     * The decision the task was completed with.
     *
     * @return an outcome, or {@code null} when nothing was decided
     */
    @Nullable
    public String getOutcome()
    {
        return this.outcome;
    }

    /**
     * What whoever decided said about their decision. Nothing routes on this, which is exactly why it has to be
     * recorded rather than left to be remembered.
     *
     * @return a note, or {@code null} when none was given
     */
    @Nullable
    public String getOutcomeNote()
    {
        return this.outcomeNote;
    }

    /**
     * The name of the event that was delivered.
     *
     * @return an event name, or {@code null} when the action was not event-driven
     */
    @Nullable
    public String getEvent()
    {
        return this.event;
    }

    /**
     * The component responsible when no workflow was — a scheduled sweep, a migration, an import.
     *
     * @return a component name, or {@code null} for a workflow-driven action
     */
    @Nullable
    public String getComponent()
    {
        return this.component;
    }

    /**
     * The action this one was performed as part of, for actions that nest.
     *
     * @return an identifier, or {@code null} for a top-level action
     */
    @Nullable
    public String getParentAction()
    {
        return this.parentAction;
    }

    /**
     * Whether every snapshot this action set out to take was taken.
     *
     * <p>
     * It has to be asked because the snapshots cannot be part of the action's own commit: a check-in refuses to run
     * while the session has pending changes, and commits by itself. So while this is {@code false}, an {@link Entry}
     * without a {@link Entry#getSnapshot() snapshot} may simply not have got one yet; once it is {@code true}, an entry
     * without one never wanted one.
     * </p>
     *
     * @return {@code true} once the snapshots are done with, {@code false} while they may still be outstanding
     */
    public boolean isComplete()
    {
        return this.complete != null && this.complete;
    }

    /**
     * What this action did, one entry per resource it affected.
     *
     * @return the entries, possibly empty, never {@code null}
     */
    @NotNull
    public List<Entry> getEntries()
    {
        return this.getChildren(Entry.RESOURCE_TYPE, Entry.class);
    }
}
