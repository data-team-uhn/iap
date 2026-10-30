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
package io.uhndata.iap.history.api;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A request somebody made and what it did, described so that {@link HistoryRecorder} can store it as a
 * {@link io.uhndata.iap.history.models.Action}.
 *
 * <p>
 * Built through {@link #by(String, String)}, which takes the two things every action has: who asked, and what they
 * asked for. The {@link Builder}'s methods add whatever else the request carried, and say what each value must be.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class RecordedAction
{
    private final String requester;

    private final String operation;

    private final String onBehalfOf;

    private final String workflowInstance;

    private final String workflowVersion;

    private final String activityId;

    private final String activityLabel;

    private final String taskInstance;

    private final String outcome;

    private final String outcomeNote;

    private final String event;

    private final String component;

    private final String parentAction;

    private final Calendar occurredAt;

    private final List<RecordedEffect> effects;

    private RecordedAction(final Builder builder)
    {
        this.requester = builder.requester;
        this.operation = builder.operation;
        this.onBehalfOf = builder.onBehalfOf;
        this.workflowInstance = builder.workflowInstance;
        this.workflowVersion = builder.workflowVersion;
        this.activityId = builder.activityId;
        this.activityLabel = builder.activityLabel;
        this.taskInstance = builder.taskInstance;
        this.outcome = builder.outcome;
        this.outcomeNote = builder.outcomeNote;
        this.event = builder.event;
        this.component = builder.component;
        this.parentAction = builder.parentAction;
        this.occurredAt = builder.occurredAt;
        this.effects = List.copyOf(builder.effects);
    }

    /**
     * Starts describing an action.
     *
     * @param requester the canonical username of the person who asked: the repository's spelling of their account
     *            name, which can differ from what they typed at login
     * @param operation a short verb naming what was asked for, for a reader of the log, such as {@code activate} or
     *            {@code purge}; it need not be the name of the workflow event that carried the request
     * @return a builder for the rest of the action
     */
    @NotNull
    public static Builder by(@NotNull final String requester, @NotNull final String operation)
    {
        return new Builder(requester, operation);
    }

    /**
     * Who asked for this action.
     *
     * @return the canonical username given to {@link #by}
     */
    @NotNull
    public String getRequestedBy()
    {
        return this.requester;
    }

    /**
     * What was asked for.
     *
     * @return the verb given to {@link #by}
     */
    @NotNull
    public String getOperation()
    {
        return this.operation;
    }

    /**
     * Whom the requester acted on behalf of.
     *
     * @return whom the requester acted on behalf of, as a canonical username (if the user has a local account), or an
     *         email address or URI or another way to identify an external user, or {@code null} if the requester
     *         didn't act on behalf of another person
     */
    @Nullable
    public String getOnBehalfOf()
    {
        return this.onBehalfOf;
    }

    /**
     * The content workflow instance this action started or advanced.
     *
     * @return the instance's path, as given to {@link Builder#workflow}, or {@code null} if the action involved no
     *         content workflow instance
     */
    @Nullable
    public String getWorkflowInstance()
    {
        return this.workflowInstance;
    }

    /**
     * The version of the workflow that handled the request.
     *
     * @return the {@code jcr:uuid} of that {@code wf:WorkflowVersion}, as given to {@link Builder#workflow}, or
     *         {@code null} if no workflow handled the request
     */
    @Nullable
    public String getWorkflowVersion()
    {
        return this.workflowVersion;
    }

    /**
     * The step of that workflow version that handled the request.
     *
     * @return the step's {@code elementId}, as given to {@link Builder#activity}, or {@code null} if no workflow
     *         handled the request
     */
    @Nullable
    public String getActivityId()
    {
        return this.activityId;
    }

    /**
     * The label of that step.
     *
     * @return the label the workflow version gave the step, or {@code null} if the step has none or no workflow
     *         handled the request
     */
    @Nullable
    public String getActivityLabel()
    {
        return this.activityLabel;
    }

    /**
     * The task whose completion caused this action.
     *
     * @return the path of that {@code wf:TaskInstance}, as given to {@link Builder#task}, or {@code null} if the
     *         action completed no task
     */
    @Nullable
    public String getTaskInstance()
    {
        return this.taskInstance;
    }

    /**
     * The outcome the task was completed with.
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
     * What the person who completed the task wrote about their decision.
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
     * The component that acted, when no workflow handled the request.
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
     * @return the path {@link HistoryRecorder#record} returned for that action, or {@code null} for an action that
     *         is not part of another
     */
    @Nullable
    public String getParentAction()
    {
        return this.parentAction;
    }

    /**
     * When the action happened, for a record written later than that.
     *
     * @return a copy of the time given to {@link Builder#occurredAt}, or {@code null} if the action happened when it
     *         is recorded
     */
    @Nullable
    public Calendar getOccurredAt()
    {
        return this.occurredAt == null ? null : (Calendar) this.occurredAt.clone();
    }

    /**
     * What the action did to each resource it affected.
     *
     * @return one effect per affected resource, in the order they were added; empty if the action affected none
     */
    @NotNull
    public List<RecordedEffect> getEffects()
    {
        return this.effects;
    }

    /**
     * Collects what an action carried beyond who asked and what they asked for. Every method may be skipped, and
     * passing {@code null} to one is the same as skipping it.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class Builder
    {
        private final String requester;

        private final String operation;

        private final List<RecordedEffect> effects = new ArrayList<>();

        private String onBehalfOf;

        private String workflowInstance;

        private String workflowVersion;

        private String activityId;

        private String activityLabel;

        private String taskInstance;

        private String outcome;

        private String outcomeNote;

        private String event;

        private String component;

        private String parentAction;

        private Calendar occurredAt;

        private Builder(final String requester, final String operation)
        {
            this.requester = Objects.requireNonNull(requester, "an action has to say who asked for it");
            this.operation = Objects.requireNonNull(operation, "an action has to say what was asked for");
        }

        /**
         * Records that the requester acted on behalf of another person, such as a delegate approving for a reviewer.
         *
         * @param person the canonical username of that person if they have a local account, or an email address or
         *            URI or another way to identify an external user; {@code null} if the requester acted for
         *            themselves
         * @return this builder
         */
        @NotNull
        public Builder onBehalfOf(@Nullable final String person)
        {
            this.onBehalfOf = person;
            return this;
        }

        /**
         * Records the workflows involved: the content workflow instance the action started or advanced, and the
         * version of the workflow that handled the request.
         *
         * <p>
         * The two can belong to different workflows. Saving a submission's answers is handled by a system workflow,
         * and advances no content workflow instance. Completing a task is handled by the content workflow the task
         * belongs to, which is also the instance advanced. Creating a submission is handled by a system workflow,
         * which starts the submission's content workflow: the version is the system workflow's, the instance the
         * content workflow's.
         * </p>
         *
         * @param instancePath the path of the {@code wf:WorkflowInstance}; {@code null} if the action started or
         *            advanced no content workflow instance
         * @param versionId the {@code jcr:uuid} of the {@code wf:WorkflowVersion} that handled the request;
         *            {@code null} if no workflow handled it, as for a scheduled sweep
         * @return this builder
         */
        @NotNull
        public Builder workflow(@Nullable final String instancePath, @Nullable final String versionId)
        {
            this.workflowInstance = instancePath;
            this.workflowVersion = versionId;
            return this;
        }

        /**
         * Records which step of the workflow version given to {@link #workflow} handled the request.
         *
         * @param elementId the step's {@code elementId}: the user task that was completed, or the message start event
         *            of the system workflow that caught the request; {@code null} if no workflow handled it
         * @param label the label that workflow version gives the step, copied because the step may be reworded in a
         *            later version; {@code null} if the step has none
         * @return this builder
         */
        @NotNull
        public Builder activity(@Nullable final String elementId, @Nullable final String label)
        {
            this.activityId = elementId;
            this.activityLabel = label;
            return this;
        }

        /**
         * Records the task whose completion caused this action, and the decision taken on it.
         *
         * @param taskPath the path of the {@code wf:TaskInstance} that was completed; {@code null} if the action
         *            completed no task
         * @param decision the outcome it was completed with, one of the task's {@code outcomeOptions}; {@code null}
         *            if the task offered no choice
         * @param note what the person who completed it wrote about their decision, exactly as written; {@code null}
         *            if they wrote nothing
         * @return this builder
         */
        @NotNull
        public Builder task(@Nullable final String taskPath, @Nullable final String decision,
            @Nullable final String note)
        {
            this.taskInstance = taskPath;
            this.outcome = decision;
            this.outcomeNote = note;
            return this;
        }

        /**
         * Records the workflow event that carried the request.
         *
         * @param name the event's name, such as {@code save} or {@code complete}; {@code null} if no workflow event
         *            carried it
         * @return this builder
         */
        @NotNull
        public Builder event(@Nullable final String name)
        {
            this.event = name;
            return this;
        }

        /**
         * Records the component that acted, for an action no workflow handled, such as a scheduled sweep, a migration
         * or an import.
         *
         * @param className the component's fully qualified class name; {@code null} if a workflow handled the request
         * @return this builder
         */
        @NotNull
        public Builder component(@Nullable final String className)
        {
            this.component = className;
            return this;
        }

        /**
         * Records that this action was performed as part of another.
         *
         * @param actionPath the path {@link HistoryRecorder#record} returned for the other action; {@code null} if
         *            this action is not part of another
         * @return this builder
         */
        @NotNull
        public Builder partOf(@Nullable final String actionPath)
        {
            this.parentAction = actionPath;
            return this;
        }

        /**
         * Records when the action happened, for a record written later than that, such as an imported log.
         *
         * @param when when the action happened; {@code null} if it happens as it is recorded
         * @return this builder
         */
        @NotNull
        public Builder occurredAt(@Nullable final Calendar when)
        {
            this.occurredAt = when == null ? null : (Calendar) when.clone();
            return this;
        }

        /**
         * Adds what the action did to one resource. Each resource may be named once per action.
         *
         * @param effect the resource, the part it played, and the properties that changed on it
         * @return this builder
         */
        @NotNull
        public Builder affecting(@NotNull final RecordedEffect effect)
        {
            this.effects.add(Objects.requireNonNull(effect));
            return this;
        }

        /**
         * Builds the described action. The builder can be reused afterwards without changing what it built.
         *
         * @return the described action
         */
        @NotNull
        public RecordedAction build()
        {
            return new RecordedAction(this);
        }
    }
}
