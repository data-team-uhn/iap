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
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One thing that was asked for, described so that it can be recorded.
 *
 * <p>
 * An action is one event delivery, not one step of whatever carried it out. A workflow run is committed all at once, so
 * its steps are not separately committed and are not separately recordable; describing each of them as an action would
 * also mean the record changed shape whenever somebody split a definition's service task in two.
 * </p>
 *
 * <p>
 * Built through {@link #by(String, String)}, because the two things every action must say — who and what — are worth
 * being unable to forget, while the rest depends on what caused it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class RecordedAction
{
    private final String actor;

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

    private final List<RecordedEffect> effects;

    private RecordedAction(final Builder builder)
    {
        this.actor = builder.actor;
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
        this.effects = List.copyOf(builder.effects);
    }

    /**
     * Starts describing an action.
     *
     * @param actor who is answerable for it, as a <em>canonical</em> user id — what the repository calls them, not the
     *            spelling they typed at login, since those differ and two spellings of one person is a record that
     *            cannot be totalled
     * @param operation what was asked for, in the platform's own vocabulary: {@code submit},
     *            {@code activateVersion}
     * @return a builder for the rest of it
     */
    @NotNull
    public static Builder by(@NotNull final String actor, @NotNull final String operation)
    {
        return new Builder(actor, operation);
    }

    /**
     * Who is answerable for this action.
     *
     * @return who is answerable
     */
    @NotNull
    public String getActor()
    {
        return this.actor;
    }

    /**
     * What was asked for.
     *
     * @return what was asked for
     */
    @NotNull
    public String getOperation()
    {
        return this.operation;
    }

    /**
     * Whom the actor was acting for.
     *
     * @return whom the actor acted for, or {@code null}
     */
    @Nullable
    public String getOnBehalfOf()
    {
        return this.onBehalfOf;
    }

    /**
     * The workflow instance this action moved.
     *
     * @return the instance that was moved, or {@code null}
     */
    @Nullable
    public String getWorkflowInstance()
    {
        return this.workflowInstance;
    }

    /**
     * The definition version that was in force.
     *
     * @return the definition version in force, or {@code null}
     */
    @Nullable
    public String getWorkflowVersion()
    {
        return this.workflowVersion;
    }

    /**
     * The definition node that was being performed.
     *
     * @return the definition node performed, or {@code null}
     */
    @Nullable
    public String getActivityId()
    {
        return this.activityId;
    }

    /**
     * That node's label as the definition read at the time.
     *
     * @return that node's label at the time, or {@code null}
     */
    @Nullable
    public String getActivityLabel()
    {
        return this.activityLabel;
    }

    /**
     * The task whose completion caused this.
     *
     * @return the task completed, or {@code null}
     */
    @Nullable
    public String getTaskInstance()
    {
        return this.taskInstance;
    }

    /**
     * The decision the task was completed with.
     *
     * @return the decision taken, or {@code null}
     */
    @Nullable
    public String getOutcome()
    {
        return this.outcome;
    }

    /**
     * What whoever decided said about it.
     *
     * @return what was said about the decision, or {@code null}
     */
    @Nullable
    public String getOutcomeNote()
    {
        return this.outcomeNote;
    }

    /**
     * The name of the event that was delivered.
     *
     * @return the event delivered, or {@code null}
     */
    @Nullable
    public String getEvent()
    {
        return this.event;
    }

    /**
     * The component responsible, when no workflow was.
     *
     * @return the component responsible when no workflow was, or {@code null}
     */
    @Nullable
    public String getComponent()
    {
        return this.component;
    }

    /**
     * The action this one was performed as part of.
     *
     * @return the action this was part of, or {@code null}
     */
    @Nullable
    public String getParentAction()
    {
        return this.parentAction;
    }

    /**
     * What the action did, one entry per resource it affected.
     *
     * @return the effects, possibly empty, never {@code null}
     */
    @NotNull
    public List<RecordedEffect> getEffects()
    {
        return this.effects;
    }

    /**
     * Collects the optional parts of an action.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class Builder
    {
        private final String actor;

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

        private Builder(final String actor, final String operation)
        {
            this.actor = Objects.requireNonNull(actor, "an action has to say who is answerable for it");
            this.operation = Objects.requireNonNull(operation, "an action has to say what was asked for");
        }

        /**
         * Records that the actor was acting for somebody else.
         *
         * @param principal whom the actor was acting for
         * @return this builder
         */
        @NotNull
        public Builder onBehalfOf(@Nullable final String principal)
        {
            this.onBehalfOf = principal;
            return this;
        }

        /**
         * Records which workflow, and which exact version of it, was being run.
         *
         * @param instance the identifier of the instance that was moved
         * @param version the identifier of the definition version in force
         * @return this builder
         */
        @NotNull
        public Builder workflow(@Nullable final String instance, @Nullable final String version)
        {
            this.workflowInstance = instance;
            this.workflowVersion = version;
            return this;
        }

        /**
         * Records which node of the definition was being performed.
         *
         * @param id the {@code elementId} of the definition node being performed
         * @param label its label as the definition reads now, copied because the reader may not be able to read the
         *            definition and the definition may later be reworded
         * @return this builder
         */
        @NotNull
        public Builder activity(@Nullable final String id, @Nullable final String label)
        {
            this.activityId = id;
            this.activityLabel = label;
            return this;
        }

        /**
         * Records the decision that caused this, and what was said about it.
         *
         * @param instance the identifier of the task that was completed
         * @param decision the outcome it was completed with
         * @param note what whoever decided said about it
         * @return this builder
         */
        @NotNull
        public Builder task(@Nullable final String instance, @Nullable final String decision,
            @Nullable final String note)
        {
            this.taskInstance = instance;
            this.outcome = decision;
            this.outcomeNote = note;
            return this;
        }

        /**
         * Records the name of the event that was delivered.
         *
         * @param name the name of the event that was delivered
         * @return this builder
         */
        @NotNull
        public Builder event(@Nullable final String name)
        {
            this.event = name;
            return this;
        }

        /**
         * Records the component responsible, for an action no workflow caused.
         *
         * @param name the component responsible, for an action no workflow caused — a sweep, a migration, an import
         * @return this builder
         */
        @NotNull
        public Builder component(@Nullable final String name)
        {
            this.component = name;
            return this;
        }

        /**
         * Records that this action was performed as part of another.
         *
         * @param identifier the action this one was performed as part of
         * @return this builder
         */
        @NotNull
        public Builder partOf(@Nullable final String identifier)
        {
            this.parentAction = identifier;
            return this;
        }

        /**
         * Adds what the action did to one resource.
         *
         * @param effect what the action did to one resource
         * @return this builder
         */
        @NotNull
        public Builder affecting(@NotNull final RecordedEffect effect)
        {
            this.effects.add(Objects.requireNonNull(effect));
            return this;
        }

        /**
         * Builds the described action.
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
