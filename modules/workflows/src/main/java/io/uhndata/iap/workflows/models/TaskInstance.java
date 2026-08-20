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
package io.uhndata.iap.workflows.models;

import java.util.Calendar;
import java.util.List;
import java.util.Optional;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.Entity;

/**
 * A Sling Model wrapping a {@code wf:TaskInstance} node: a concrete piece of work raised by a running workflow and
 * waiting on a person. An entity in its own right rather than a part of the instance, because a task is something
 * people go looking for — "what is on my desk" is a query over these, not a walk of the workflows that raised them.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = TaskInstance.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class TaskInstance extends Entity
{
    /** The {@code sling:resourceType} of a {@code wf:TaskInstance} node. */
    public static final String RESOURCE_TYPE = "wf/TaskInstance";

    @ValueMapValue
    private String taskDefinitionId;

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String assignee;

    @ValueMapValue
    private String status;

    @ValueMapValue
    private Calendar startTime;

    @ValueMapValue
    private Calendar endTime;

    @ValueMapValue
    private Calendar dueDate;

    @ValueMapValue
    private String outcome;

    @ValueMapValue
    private String outcomeNote;

    @ValueMapValue
    private String dueEventId;

    @ValueMapValue
    private String[] outcomeOptions;

    @ValueMapValue
    private String[] performers;

    @ValueMapValue
    private String[] firedEvents;

    /**
     * The {@link FlowNode#getElementId() element identifier} of the {@link Activity} this task was raised from.
     *
     * @return a BPMN element identifier
     */
    @NotNull
    public String getTaskDefinitionId()
    {
        return this.taskDefinitionId;
    }

    /**
     * The name this task is shown under in a task list.
     *
     * @return a label
     */
    @NotNull
    public String getLabel()
    {
        return this.label;
    }

    /**
     * Who is expected to deal with this task.
     *
     * @return a user name, or {@code null} if the task has not been assigned to anyone in particular
     */
    @Nullable
    public String getAssignee()
    {
        return this.assignee;
    }

    /**
     * Where this task stands, e.g. {@code created}, {@code claimed}, {@code completed} or {@code cancelled}.
     *
     * @return a status
     */
    @NotNull
    public String getStatus()
    {
        return this.status;
    }

    /**
     * When this task became active.
     *
     * @return a copy of the start date, or {@code null} if not recorded
     */
    @Nullable
    public Calendar getStartTime()
    {
        // A copy, since Calendar is mutable and callers must not be able to alter the model's own state
        return this.startTime == null ? null : (Calendar) this.startTime.clone();
    }

    /**
     * When this task was completed or cancelled.
     *
     * @return a copy of the end date, or {@code null} if this task is still open
     */
    @Nullable
    public Calendar getEndTime()
    {
        // A copy, since Calendar is mutable and callers must not be able to alter the model's own state
        return this.endTime == null ? null : (Calendar) this.endTime.clone();
    }

    /**
     * When this task is expected to be dealt with by.
     *
     * @return a copy of the deadline, or {@code null} if this task has no deadline
     */
    @Nullable
    public Calendar getDueDate()
    {
        // A copy, since Calendar is mutable and callers must not be able to alter the model's own state
        return this.dueDate == null ? null : (Calendar) this.dueDate.clone();
    }

    /**
     * What the person dealt with this task by deciding, e.g. {@code approved}. This is what the gateway downstream
     * of the task routes on, which is why it is recorded separately from the {@link #getStatus() status}: the
     * status says the task is over, the outcome says how.
     *
     * @return an outcome, or {@code null} if this task has not been completed
     */
    @Nullable
    public String getOutcome()
    {
        return this.outcome;
    }

    /**
     * What whoever completed this task said about their decision.
     *
     * <p>Nothing routes on it — the {@link #getOutcome() outcome} is what a gateway reads — which is the point:
     * a refusal usually has to say why and an approval may carry a condition, and none of that is expressible as
     * one of the outcomes on offer.</p>
     *
     * @return what they said, or {@code null} if they said nothing or the task is not yet done
     */
    @Nullable
    public String getOutcomeNote()
    {
        return this.outcomeNote;
    }

    /**
     * The boundary event whose timer set this task's {@link #getDueDate() deadline}, named by its element
     * identifier. Recorded because an activity may be watched by several events, and what happens when the
     * deadline passes is where execution goes next — read from the definition rather than guessed at.
     *
     * @return an element identifier, or {@code null} when nothing is counting down to this task
     */
    @Nullable
    public String getDueEventId()
    {
        return this.dueEventId;
    }

    /**
     * The decisions this task may be completed with, as its {@link Activity#getOutcomeOptions() defining activity}
     * offered them when the task was raised.
     *
     * <p>Copied onto the task rather than looked up, for the same reason the {@link #getLabel() label} is: a task is
     * decided on the terms it was raised with rather than on terms the definition may have grown since, and whoever
     * has to do it can read the task without necessarily being able to read the workflow it came from.</p>
     *
     * @return the outcomes on offer, empty when completing this task is not a decision
     */
    @NotNull
    public List<String> getOutcomeOptions()
    {
        return this.outcomeOptions == null ? List.of() : List.of(this.outcomeOptions);
    }

    /**
     * The principals who may complete this task, as its {@link FlowNode#getPerformers() defining activity} named them
     * when the task was raised, with {@code @creator} already answered against the host it drives.
     *
     * <p>Recorded on the task so that "what is waiting for me" can be asked of tasks alone: whoever owes the decision
     * can rarely read the workflow it came from, and a listing cannot run the engine per row to find out. It is a
     * description and not a permission — every completion is still checked against the definition — so finding a task
     * here is not what makes it lawful to complete.</p>
     *
     * @return the principals the task admits, empty when it names none, which admits nobody
     */
    @NotNull
    public List<String> getPerformers()
    {
        return this.performers == null ? List.of() : List.of(this.performers);
    }

    /**
     * The boundary events watching this task that have already fired, named by their
     * {@link FlowNode#getElementId() element identifiers}.
     *
     * <p>Only a non-interrupting event can fire and leave the task open behind it, which is what makes this needed:
     * the deadline that has passed would otherwise be delivered again on every sweep, and a task watched by several
     * events would have no way to say which of them {@link #getDueDate() its deadline} now belongs to.</p>
     *
     * @return the identifiers of the events that have fired, empty for a task nothing has happened to yet
     */
    @NotNull
    public List<String> getFiredEvents()
    {
        return this.firedEvents == null ? List.of() : List.of(this.firedEvents);
    }

    /**
     * The instance that raised this task, which is simply its parent.
     *
     * @return the owning workflow instance, or {@code null} if this task is stored outside one
     */
    @Nullable
    public WorkflowInstance getWorkflowInstance()
    {
        return this.getParent(WorkflowInstance.RESOURCE_TYPE, WorkflowInstance.class);
    }

    /**
     * The activity in the workflow definition this task was raised from, resolved by looking its
     * {@link #getTaskDefinitionId() identifier} up in the version its instance is executing.
     *
     * @return the defining activity, or {@code null} if the instance, its version, or the named node cannot be
     *         resolved, or that node is not an activity
     */
    @Nullable
    public Activity getDefinition()
    {
        return Optional.ofNullable(this.getWorkflowInstance())
            .map(WorkflowInstance::getWorkflowVersion)
            .map(version -> version.getFlowNode(this.taskDefinitionId))
            // Both checks are needed: arriving as an Activity says only which model was built, and a node of an
            // unclaimed resource type is built as one without being one, so the node's own type has the last word
            .filter(Activity.class::isInstance)
            .filter(node -> node.isOfType(Activity.RESOURCE_TYPE))
            .map(Activity.class::cast)
            .orElse(null);
    }
}
