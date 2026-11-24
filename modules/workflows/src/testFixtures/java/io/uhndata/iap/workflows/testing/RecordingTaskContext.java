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
package io.uhndata.iap.workflows.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Lets a test call a service task handler directly, without the engine. It is the context the handler runs in, and
 * it answers with what the test gave its {@link Builder}.
 *
 * <p>It records what the handler left behind: the variables it set, the roles it recorded for the history and the
 * events it sent on. It also keeps the work left for after the commit and the actions registered for a rollback.
 * Nothing recorded runs until the test asks. {@link #runAfterCommit()} runs the work in the order the handler left
 * it, and {@link #runRollback()} runs the actions in the reverse order, as the engine does.</p>
 *
 * <p>Starting a workflow needs the engine, so {@link #startWorkflow} and {@link #replaceWorkflow} throw
 * {@link IllegalStateException}.</p>
 *
 * <p>A module's tests use it through the {@code iap-workflows} test jar, in the module's {@code tests} profile.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class RecordingTaskContext implements WorkflowTaskContext
{
    /** The path of the activity a context performs when the test names none. */
    public static final String UNCONFIGURED_ACTIVITY_PATH = "/SystemWorkflows/test/v1/task";

    private final Resource target;

    private final ResourceResolver resolver;

    private final String actor;

    private final WorkflowEvent event;

    private final Activity activity;

    private final Map<String, Object> variables;

    private final List<RecordedRole> roles = new ArrayList<>();

    private final List<SentEvent> sentEvents = new ArrayList<>();

    private final List<Runnable> afterCommitWork = new ArrayList<>();

    private final List<Runnable> rollbackActions = new ArrayList<>();

    private RecordingTaskContext(final Builder builder)
    {
        this.target = builder.target;
        this.resolver = builder.resolver;
        this.actor = builder.actor;
        this.event = builder.event;
        this.activity = builder.activity == null ? unconfiguredActivity() : builder.activity;
        this.variables = new HashMap<>(builder.variables);
    }

    private static Activity unconfiguredActivity()
    {
        final Activity unconfigured = Mockito.mock(Activity.class);
        Mockito.when(unconfigured.getPath()).thenReturn(UNCONFIGURED_ACTIVITY_PATH);
        try {
            Mockito.doCallRealMethod().when(unconfigured).actsOn(Mockito.any());
        } catch (final WorkflowDefinitionException e) {
            throw new IllegalStateException("Stubbing a method never runs it", e);
        }
        return unconfigured;
    }

    /**
     * Starts describing a context for an event aimed at a resource.
     *
     * @param target the resource the event is aimed at
     * @return a builder for an event named {@code test} with an empty payload, sent by {@code admin}, performing an
     *         activity that configures nothing, and working through the target's own resource resolver
     */
    @NotNull
    public static Builder aimedAt(@NotNull final Resource target)
    {
        return new Builder(target);
    }

    @Override
    @NotNull
    public Resource getTarget()
    {
        return this.target;
    }

    @Override
    @NotNull
    public String getActor()
    {
        return this.actor;
    }

    @Override
    @NotNull
    public WorkflowEvent getEvent()
    {
        return this.event;
    }

    @Override
    @NotNull
    public Activity getActivity()
    {
        return this.activity;
    }

    @Override
    @Nullable
    public Object getVariable(@NotNull final String name)
    {
        return this.variables.get(name);
    }

    @Override
    public void setVariable(@NotNull final String name, @Nullable final Object value)
    {
        this.variables.put(name, value);
    }

    /**
     * Every variable of the execution: those the builder started it with, as the handler left them.
     *
     * @return an unmodifiable view of the variables by name, empty if there are none
     */
    @NotNull
    public Map<String, Object> getVariables()
    {
        return Collections.unmodifiableMap(this.variables);
    }

    @Override
    @NotNull
    public ResourceResolver getResourceResolver()
    {
        if (this.resolver != null) {
            return this.resolver;
        }
        final ResourceResolver own = this.target.getResourceResolver();
        if (own == null) {
            throw new IllegalStateException("The target " + this.target + " has no resource resolver of its own,"
                + " so the context needs one through Builder.resolvingThrough");
        }
        return own;
    }

    @Override
    public void sendEvent(@NotNull final Resource to, @NotNull final WorkflowEvent sent)
    {
        this.sentEvents.add(new SentEvent(to, sent));
    }

    /**
     * The events the handler sent on, none of which was delivered.
     *
     * @return the events in the order they were sent, empty if none was
     */
    @NotNull
    public List<SentEvent> getSentEvents()
    {
        return Collections.unmodifiableList(this.sentEvents);
    }

    @Override
    public void startWorkflow(@NotNull final Resource host, @NotNull final WorkflowVersion version)
    {
        throw new IllegalStateException("Starting a workflow needs the engine");
    }

    @Override
    public void replaceWorkflow(@NotNull final Resource host, @NotNull final WorkflowVersion version)
    {
        throw new IllegalStateException("Replacing a workflow needs the engine");
    }

    @Override
    public void recordRole(@NotNull final Resource resource, @NotNull final String role)
    {
        this.roles.add(new RecordedRole(resource.getPath(), role));
    }

    /**
     * The roles the handler recorded for the history, one entry for each call, so a repeated role shows twice.
     *
     * @return the roles in the order they were recorded, empty if the handler recorded none
     */
    @NotNull
    public List<RecordedRole> getRoles()
    {
        return Collections.unmodifiableList(this.roles);
    }

    @Override
    public void afterCommit(@NotNull final Runnable work)
    {
        this.afterCommitWork.add(work);
    }

    /**
     * The work the handler left for after the commit, none of which has run.
     *
     * @return the work in the order the handler left it, empty if it left none
     */
    @NotNull
    public List<Runnable> getAfterCommitWork()
    {
        return Collections.unmodifiableList(this.afterCommitWork);
    }

    /**
     * Runs the work left for after the commit, as the engine does once the delivery commits: in the order the handler
     * left it. A failure stops the run and reaches the test.
     */
    public void runAfterCommit()
    {
        this.afterCommitWork.forEach(Runnable::run);
    }

    @Override
    public void onRollback(@NotNull final Runnable action)
    {
        this.rollbackActions.add(action);
    }

    /**
     * The actions the handler registered to undo its side effects, none of which has run.
     *
     * @return the actions in the order the handler registered them, empty if it registered none
     */
    @NotNull
    public List<Runnable> getRollbackActions()
    {
        return Collections.unmodifiableList(this.rollbackActions);
    }

    /**
     * Runs the actions registered for a rollback, as the engine does when the delivery does not commit: the last one
     * registered first. A failure stops the run and reaches the test.
     */
    public void runRollback()
    {
        this.rollbackActions.reversed().forEach(Runnable::run);
    }

    /**
     * A role the handler recorded for the history.
     *
     * @param path the path of the resource the role was recorded for
     * @param role the word for the part it played, such as {@code retired}
     * @version $Id$
     * @since 0.1.0
     */
    public record RecordedRole(@NotNull String path, @NotNull String role)
    {
    }

    /**
     * An event the handler sent on.
     *
     * @param target the resource the event was sent to
     * @param event the event sent, with its name and payload
     * @version $Id$
     * @since 0.1.0
     */
    public record SentEvent(@NotNull Resource target, @NotNull WorkflowEvent event)
    {
    }

    /**
     * Describes a {@link RecordingTaskContext}.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class Builder
    {
        private final Resource target;

        private final Map<String, Object> variables = new HashMap<>();

        private ResourceResolver resolver;

        private String actor = "admin";

        private WorkflowEvent event = new WorkflowEvent("test", Map.of());

        private Activity activity;

        private Builder(final Resource target)
        {
            this.target = target;
        }

        /**
         * Names the event and what it carries.
         *
         * @param name the event name, such as {@code create}
         * @param payload the data the event carries, by key, with no {@code null} keys or values
         * @return this builder
         */
        @NotNull
        public Builder forEvent(@NotNull final String name, @NotNull final Map<String, Object> payload)
        {
            this.event = new WorkflowEvent(name, payload);
            return this;
        }

        /**
         * Names who sent the event.
         *
         * @param user the repository user id of the sender, such as {@code demo-requester}
         * @return this builder
         */
        @NotNull
        public Builder actingFor(@NotNull final String user)
        {
            this.actor = user;
            return this;
        }

        /**
         * Names the activity the handler performs, whose properties are its configuration.
         *
         * @param performed the activity, often a Mockito mock answering the properties the handler reads
         * @return this builder
         */
        @NotNull
        public Builder performing(@NotNull final Activity performed)
        {
            this.activity = performed;
            return this;
        }

        /**
         * Has the handler work through another resource resolver than the target's own.
         *
         * @param session the resource resolver to hand out, such as a mock or a wrapper over the test context's own
         * @return this builder
         */
        @NotNull
        public Builder resolvingThrough(@NotNull final ResourceResolver session)
        {
            this.resolver = session;
            return this;
        }

        /**
         * Sets a variable an earlier step of the execution left behind.
         *
         * @param name the variable name
         * @param value the value; {@code null} to set the variable without a value
         * @return this builder
         */
        @NotNull
        public Builder withVariable(@NotNull final String name, @Nullable final Object value)
        {
            this.variables.put(name, value);
            return this;
        }

        /**
         * Records that an earlier step of the execution created a resource, which the handler then acts on.
         *
         * @param path the path of the created resource, or {@code null} for an execution that created nothing
         * @return this builder
         */
        @NotNull
        public Builder havingCreated(@Nullable final String path)
        {
            return withVariable(WorkflowResult.CREATED_PATH_VARIABLE, path);
        }

        /**
         * Builds the context.
         *
         * @return a context answering as described, with nothing recorded yet
         */
        @NotNull
        public RecordingTaskContext build()
        {
            return new RecordingTaskContext(this);
        }
    }
}
