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

import java.io.Closeable;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.utils.UserIds;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * What the engine answers without changing anything: which events a user could send to a target, and which
 * system workflow would handle one of them.
 *
 * <p>Both are decided through the engine's own session, as receiving the event would decide them, since a guard may
 * look at content the asking user cannot read. Only event names, and the user's own view of a definition, are handed
 * back, so nothing the engine's session can reach ever leaves it. That is what lets one engine session answer
 * everything asked through one resolver: a serialization asks about every content node it writes, and a login per
 * question would cost one per node. The session is kept in the resolver's
 * {@link ResourceResolver#getPropertyMap() property map}, which closes it along with the resolver, so the one a
 * request resolved through lasts until the request ends.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class WorkflowQueries
{
    private WorkflowQueries()
    {
    }

    /**
     * The events the asking user could send to a target right now.
     *
     * @param target the target, resolved through the asking user's own session
     * @param login opens the engine's own session, when the asking resolver does not hold one yet
     * @param evaluator decides whether guards hold
     * @return the event names, in alphabetical order
     * @throws WorkflowException when the engine's session cannot be opened, the user cannot be looked up, or several
     *             workflows would take one of the events
     */
    static Set<String> availableEvents(final Resource target, final ServiceLogin login,
        final ConditionEvaluator evaluator) throws WorkflowException
    {
        final Scope scope = Scope.of(target.getResourceResolver(), login);
        final Resource privileged = scope.privileged(target);
        if (privileged.isResourceType(TaskInstance.RESOURCE_TYPE)) {
            return TaskCompletion.availableEvents(privileged, scope.performers());
        }
        return SystemWorkflowLocator.availableEvents(scope.resolver, privileged, evaluator, scope.performers());
    }

    /**
     * The system workflow that would handle an event from the asking user, as their own session reads it. Only the
     * answer is handed over to the user's session.
     *
     * @param target the target, resolved through the asking user's own session
     * @param event the event's name
     * @param login opens the engine's own session, when the asking resolver does not hold one yet
     * @param evaluator decides whether guards hold
     * @return the workflow version, or {@code null} when no system workflow would take the event from the user
     * @throws WorkflowException when the engine's session cannot be opened, the user cannot be looked up, several
     *             workflows would take the event, or the user's session cannot read the one that would
     */
    static WorkflowVersion applicableWorkflow(final Resource target, final String event, final ServiceLogin login,
        final ConditionEvaluator evaluator) throws WorkflowException
    {
        final Scope scope = Scope.of(target.getResourceResolver(), login);
        final Resource privileged = scope.privileged(target);
        if (privileged.isResourceType(TaskInstance.RESOURCE_TYPE)) {
            return null;
        }
        final StartEvent start;
        try {
            start = SystemWorkflowLocator.find(scope.resolver, privileged, new WorkflowEvent(event, Map.of()),
                evaluator);
        } catch (final NoApplicableWorkflowException e) {
            return null;
        }
        return scope.performers().admits(start) ? handOver(target.getResourceResolver(), start) : null;
    }

    /**
     * The version a matched start event belongs to, as the asking user's own session reads it.
     *
     * @param asking the asking user's own session
     * @param start the start event, backed by the engine's session
     * @return the version, backed by the asking session
     * @throws WorkflowFailedException when the asking session cannot read the version
     */
    private static WorkflowVersion handOver(final ResourceResolver asking, final StartEvent start)
        throws WorkflowFailedException
    {
        final String path = Objects.requireNonNull(start.getWorkflowVersion(),
            "A start event found in a workflow version belongs to it").getPath();
        final Resource version = asking.getResource(path);
        if (version == null) {
            throw new WorkflowFailedException("The system workflow " + path + " is not readable by "
                + UserIds.canonical(asking)
                + "; the repository lacks the grant that lets everyone read /SystemWorkflows");
        }
        return Objects.requireNonNull(version.adaptTo(WorkflowVersion.class),
            "A wf:WorkflowVersion resource always adapts to its model");
    }

    /**
     * How the queries open the engine's own session: the engine's own login, so that its service user, and what a
     * missing one means, stay defined in one place.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    interface ServiceLogin
    {
        /**
         * Opens the engine's own session.
         *
         * @return a service resource resolver, for the caller to close
         * @throws WorkflowFailedException when the service user is not available
         */
        ResourceResolver open() throws WorkflowFailedException;
    }

    /**
     * The engine's session for what one user asks through one resolver, and who that user is to the definitions.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class Scope implements Closeable
    {
        /** The engine's own session. */
        private final ResourceResolver resolver;

        /** The asking user. */
        private final String actor;

        /** Whom the definitions admit, looked up the first time a question needs it. */
        private PerformerCheck performers;

        private Scope(final ResourceResolver resolver, final String actor)
        {
            this.resolver = resolver;
            this.actor = actor;
        }

        /**
         * The scope a resolver holds for its user, opened the first time they ask something through it.
         *
         * @param asking the resolver the user asks through
         * @param login opens the engine's own session
         * @return the scope, kept in the resolver until it closes
         * @throws WorkflowFailedException when the engine's session cannot be opened
         */
        static Scope of(final ResourceResolver asking, final ServiceLogin login) throws WorkflowFailedException
        {
            final String actor = UserIds.canonical(asking);
            // Keyed by user as well: a wrapper can report another user while sharing the map of the resolver it wraps
            final String key = Scope.class.getName() + '/' + actor;
            final Map<String, Object> kept = asking.getPropertyMap();
            final Object existing = kept.get(key);
            if (existing instanceof Scope) {
                return (Scope) existing;
            }
            final Scope scope = new Scope(login.open(), actor);
            kept.put(key, scope);
            return scope;
        }

        /**
         * The target as the engine sees it.
         *
         * @param target the target as the asking user sees it
         * @return the same resource, backed by the engine's session
         */
        Resource privileged(final Resource target)
        {
            return Objects.requireNonNull(this.resolver.getResource(target.getPath()),
                "A target the caller could reach is always visible to the engine");
        }

        /**
         * Whom the definitions admit, for this scope's user.
         *
         * @return the check, looked up once per scope
         * @throws WorkflowFailedException when the repository cannot say who the user is
         */
        PerformerCheck performers() throws WorkflowFailedException
        {
            if (this.performers == null) {
                this.performers = PerformerCheck.of(this.resolver, this.actor);
            }
            return this.performers;
        }

        @Override
        public void close()
        {
            this.resolver.close();
        }
    }
}
