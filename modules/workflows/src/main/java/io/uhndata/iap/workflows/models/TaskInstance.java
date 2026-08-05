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
import java.util.Optional;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;
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
    private String form;

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
     * The form to be filled in as part of this task, when there is one.
     *
     * <p>Returned as generic {@link Content} on purpose: a form belongs to whichever module raised the task, and
     * having the workflows module know about those would make the dependency run the wrong way — everything
     * depends on workflows, not the other way round. Callers who know what they asked for adapt it themselves.</p>
     *
     * @return the referenced content, or {@code null} if this task has no form or the reference cannot be resolved
     */
    @Nullable
    public Content getForm()
    {
        return this.getReference(this.form, Content.class);
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
            .filter(Activity.class::isInstance)
            .map(Activity.class::cast)
            .orElse(null);
    }
}
