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

import java.util.Calendar;
import java.util.List;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Default;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;

/**
 * A request somebody made, as recorded in the history: who made it, what they asked for, and what handled it.
 *
 * <p>
 * An action is one event delivery, not one step of the workflow that carried it out. What the action did to each
 * resource it affected is an {@link Entry} child.
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

    /** The name of the property holding the canonical username of who asked. */
    public static final String CREATED_BY_PROPERTY = "createdBy";

    /** The name of the property holding whom the requester acted on behalf of. */
    public static final String ON_BEHALF_OF_PROPERTY = "onBehalfOf";

    /** The name of the property holding the verb naming what was asked for. */
    public static final String OPERATION_PROPERTY = "operation";

    /** The name of the property holding the path of the content workflow instance involved. */
    public static final String WORKFLOW_INSTANCE_PROPERTY = "workflowInstance";

    /** The name of the property holding the identifier of the workflow version that handled the request. */
    public static final String WORKFLOW_VERSION_PROPERTY = "workflowVersion";

    /** The name of the property holding the {@code elementId} of the workflow step that handled the request. */
    public static final String ACTIVITY_ID_PROPERTY = "activityId";

    /** The name of the property holding that step's label. */
    public static final String ACTIVITY_LABEL_PROPERTY = "activityLabel";

    /** The name of the property holding the path of the task completed. */
    public static final String TASK_INSTANCE_PROPERTY = "taskInstance";

    /** The name of the property holding the outcome the task was completed with. */
    public static final String OUTCOME_PROPERTY = "outcome";

    /** The name of the property holding what the person deciding wrote about their decision. */
    public static final String OUTCOME_NOTE_PROPERTY = "outcomeNote";

    /** The name of the property holding the name of the workflow event that carried the request. */
    public static final String EVENT_PROPERTY = "event";

    /** The name of the property holding the class name of the component that acted. */
    public static final String COMPONENT_PROPERTY = "component";

    /** The name of the property holding the path of the action this one was part of. */
    public static final String PARENT_ACTION_PROPERTY = "parentAction";

    /** The name of the property holding when the action happened, where that is not when it was recorded. */
    public static final String OCCURRED_AT_PROPERTY = "occurredAt";

    /** The name of the property saying whether every snapshot the action wanted has been taken. */
    public static final String COMPLETE_PROPERTY = "complete";

    @ValueMapValue(name = ON_BEHALF_OF_PROPERTY)
    private String onBehalfOf;

    @ValueMapValue(name = OPERATION_PROPERTY)
    private String operation;

    @ValueMapValue(name = WORKFLOW_INSTANCE_PROPERTY)
    private String workflowInstance;

    @ValueMapValue(name = WORKFLOW_VERSION_PROPERTY)
    private String workflowVersion;

    @ValueMapValue(name = ACTIVITY_ID_PROPERTY)
    private String activityId;

    @ValueMapValue(name = ACTIVITY_LABEL_PROPERTY)
    private String activityLabel;

    @ValueMapValue(name = TASK_INSTANCE_PROPERTY)
    private String taskInstance;

    @ValueMapValue(name = OUTCOME_PROPERTY)
    private String outcome;

    @ValueMapValue(name = OUTCOME_NOTE_PROPERTY)
    private String outcomeNote;

    @ValueMapValue(name = EVENT_PROPERTY)
    private String event;

    @ValueMapValue(name = COMPONENT_PROPERTY)
    private String component;

    @ValueMapValue(name = PARENT_ACTION_PROPERTY)
    private String parentAction;

    @ValueMapValue(name = OCCURRED_AT_PROPERTY)
    private Calendar occurredAt;

    @ValueMapValue(name = COMPLETE_PROPERTY)
    @Default(booleanValues = false)
    private boolean complete;

    /**
     * Who asked for this action. The record is {@link #getCreatedBy() created by} them, and this names what that
     * means for an action.
     *
     * @return the canonical username of the person who asked: the repository's spelling of their account name, which
     *         can differ from what they typed at login; empty only in a malformed record
     */
    @NotNull
    public String getRequestedBy()
    {
        return Objects.requireNonNullElse(this.getCreatedBy(), "");
    }

    /**
     * Whom the requester acted on behalf of, such as the reviewer a delegate approved for.
     *
     * @return the canonical username of that person if they have a local account, or an email address or URI or
     *         another way to identify an external user, or {@code null} if the requester acted for themselves
     */
    @Nullable
    public String getOnBehalfOf()
    {
        return this.onBehalfOf;
    }

    /**
     * What was asked for, named for a reader of the log rather than by the workflow event that carried it.
     *
     * @return a short verb, such as {@code activate} or {@code purge}; empty only in a malformed record
     */
    @NotNull
    public String getOperation()
    {
        return this.operation == null ? "" : this.operation;
    }

    /**
     * The content workflow instance this action started or advanced. It can belong to a different workflow from
     * {@link #getWorkflowVersion()}: creating a submission is handled by a system workflow, which starts the
     * submission's content workflow.
     *
     * @return the path the {@code wf:WorkflowInstance} had when the action happened, or {@code null} if the action
     *         involved no content workflow instance, as when a system workflow saved a submission's answers
     */
    @Nullable
    public String getWorkflowInstance()
    {
        return this.workflowInstance;
    }

    /**
     * The version of the workflow that handled the request. That is the system workflow that caught the event, such
     * as the one saving a submission's answers, or the content workflow whose task was completed.
     *
     * @return the {@code jcr:uuid} of that {@code wf:WorkflowVersion}, or {@code null} if no workflow handled the
     *         request, as for a scheduled sweep
     */
    @Nullable
    public String getWorkflowVersion()
    {
        return this.workflowVersion;
    }

    /**
     * The step of {@link #getWorkflowVersion() that workflow version} that handled the request. That is the user task
     * that was completed, or the message start event of the system workflow that caught the request.
     *
     * @return the step's {@code elementId}, or {@code null} if no workflow handled the request
     */
    @Nullable
    public String getActivityId()
    {
        return this.activityId;
    }

    /**
     * The label that workflow version gives {@link #getActivityId() the step}. Copied rather than looked up: the reader
     * may not be able to read the workflow, and a later version may reword the step.
     *
     * @return the label, or {@code null} if the step has none or no workflow handled the request
     */
    @Nullable
    public String getActivityLabel()
    {
        return this.activityLabel;
    }

    /**
     * The task whose completion caused this action.
     *
     * @return the path the {@code wf:TaskInstance} had when the action happened, or {@code null} if the action
     *         completed no task
     */
    @Nullable
    public String getTaskInstance()
    {
        return this.taskInstance;
    }

    /**
     * The outcome {@link #getTaskInstance() the task} was completed with.
     *
     * @return one of the task's {@code outcomeOptions}, such as {@code approved}, or {@code null} if no task was
     *         completed or the task offered no choice
     */
    @Nullable
    public String getOutcome()
    {
        return this.outcome;
    }

    /**
     * What the person who completed {@link #getTaskInstance() the task} wrote about their decision.
     *
     * @return their note, exactly as they wrote it, or {@code null} if they wrote none or no task was completed
     */
    @Nullable
    public String getOutcomeNote()
    {
        return this.outcomeNote;
    }

    /**
     * The workflow event that carried the request.
     *
     * @return the event's name, such as {@code save} or {@code complete}, or {@code null} if no workflow event
     *         carried the request
     */
    @Nullable
    public String getEvent()
    {
        return this.event;
    }

    /**
     * The component that acted when no workflow handled the request, such as a scheduled sweep, a migration or an
     * import.
     *
     * @return the component's fully qualified class name, or {@code null} if a workflow handled the request
     */
    @Nullable
    public String getComponent()
    {
        return this.component;
    }

    /**
     * The action this one was performed as part of.
     *
     * @return the path of that {@code hist:Action}, or {@code null} for an action that is not part of another
     */
    @Nullable
    public String getParentAction()
    {
        return this.parentAction;
    }

    /**
     * When the action happened: when it was recorded, unless the record says otherwise, as an imported log does.
     *
     * @return a copy of that time, or {@code null} in a malformed record, which says neither
     */
    @Nullable
    public Calendar getOccurredAt()
    {
        return this.occurredAt == null ? this.getCreated() : (Calendar) this.occurredAt.clone();
    }

    /**
     * Whether every snapshot this action set out to take was taken.
     *
     * <p>
     * While it is {@code false}, an {@link Entry} without a {@link Entry#getSnapshot() snapshot} may not have got one
     * yet. Once it is {@code true}, an entry without one never wanted one.
     * </p>
     *
     * @return {@code true} once the snapshots are done with, {@code false} while they may still be outstanding
     */
    public boolean isComplete()
    {
        return this.complete;
    }

    /**
     * What this action did to each resource it affected.
     *
     * @return one entry per affected resource, in no particular order; empty if the action affected none
     */
    @NotNull
    public List<Entry> getEntries()
    {
        return this.getChildren(Entry.RESOURCE_TYPE, Entry.class);
    }
}
