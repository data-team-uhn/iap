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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code wf:WorkflowInstance} node: one execution of a {@link WorkflowVersion}, running or
 * finished. Where the definition is the map, the instance is the journey: it holds the {@link WorkflowToken tokens}
 * saying where the execution has got to, the {@link Variable variables} it has gathered along the way, and the
 * {@link TaskInstance tasks} it has raised.
 *
 * <p>An instance is attached to whatever it is driving — a submission, say — from that side, through the
 * {@code wf:WorkflowAttachable} mixin, rather than by pointing back at it from here. One thing may therefore have
 * several workflows running over it at once.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = WorkflowInstance.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class WorkflowInstance extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code wf:WorkflowInstance} node. */
    public static final String RESOURCE_TYPE = "wf/WorkflowInstance";

    @ValueMapValue
    private String workflowVersion;

    @ValueMapValue
    private String status;

    @ValueMapValue
    private Calendar startTime;

    @ValueMapValue
    private Calendar endTime;

    /**
     * The version of the workflow this instance is executing, which is what its tokens' positions are read against.
     *
     * @return a workflow version, or {@code null} if the reference cannot be resolved
     */
    @Nullable
    public WorkflowVersion getWorkflowVersion()
    {
        return this.getReference(this.workflowVersion, WorkflowVersion.class);
    }

    /**
     * Where this instance stands as a whole, e.g. {@code active}, {@code completed}, {@code failed} or
     * {@code cancelled}.
     *
     * @return a status
     */
    @NotNull
    public String getStatus()
    {
        return this.status;
    }

    /**
     * When this instance started.
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
     * When this instance finished.
     *
     * @return a copy of the end date, or {@code null} if this instance is still running
     */
    @Nullable
    public Calendar getEndTime()
    {
        // A copy, since Calendar is mutable and callers must not be able to alter the model's own state
        return this.endTime == null ? null : (Calendar) this.endTime.clone();
    }

    /**
     * Where each branch of this execution currently rests. An instance with no tokens left has nowhere further to
     * go; more than one means branches are running concurrently.
     *
     * @return a list of tokens, empty if this instance has finished or has not started
     */
    @NotNull
    public List<WorkflowToken> getTokens()
    {
        return this.getChildren(WorkflowToken.RESOURCE_TYPE, WorkflowToken.class);
    }

    /**
     * The data this execution has gathered, keyed by name.
     *
     * @return a list of variables, empty if this instance carries no data
     */
    @NotNull
    public List<Variable> getVariables()
    {
        return this.getChildren(Variable.RESOURCE_TYPE, Variable.class);
    }

    /**
     * Looks a variable of this instance up by name. Tokens and task instances are stored alongside the variables
     * and are named by the engine just as freely, so the name alone does not say that what it finds is a variable;
     * anything else by that name reports as no variable at all.
     *
     * @param name the variable name
     * @return the matching variable, or {@code null} if this instance has no variable by that name
     */
    @Nullable
    public Variable getVariable(@NotNull final String name)
    {
        return this.getChild(name, Variable.RESOURCE_TYPE, Variable.class);
    }

    /**
     * The tasks this execution has raised, whether still open or already dealt with.
     *
     * @return a list of task instances, empty if this instance has raised none
     */
    @NotNull
    public List<TaskInstance> getTaskInstances()
    {
        return this.getChildren(TaskInstance.RESOURCE_TYPE, TaskInstance.class);
    }
}
