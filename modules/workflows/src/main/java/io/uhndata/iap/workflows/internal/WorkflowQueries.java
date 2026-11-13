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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.utils.UserIds;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * What the engine answers without changing anything. It says which events a user could send to a target, which
 * system workflow would handle one, and which task a message sent to a host is for.
 *
 * <p>The task a message is for is the engine's own question, asked as it delivers the message, and the task found goes
 * back to the engine alone. The other two are asked for a user. They are decided through the engine's own session, as
 * receiving the event would decide them, since a guard may look at content the asking user cannot read. Only event
 * names, and the user's own view of a definition, are handed back to the user, so nothing the engine's session can
 * reach ever leaves it. That is what lets one engine session answer everything asked through one resolver: a
 * serialization asks about every content node it writes, and a login per question would cost one per node. The session
 * is kept in the resolver's {@link ResourceResolver#getPropertyMap() property map}, which closes it along with the
 * resolver, so the one a request resolved through lasts until the request ends.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = WorkflowQueries.class)
public class WorkflowQueries
{
    private final ConditionEvaluator evaluator;

    private final Performers performers;

    /**
     * Constructor.
     *
     * @param evaluator decides whether guards hold
     * @param performers says who the names in a definition stand for
     */
    @Activate
    public WorkflowQueries(@Reference final ConditionEvaluator evaluator, @Reference final Performers performers)
    {
        this.evaluator = evaluator;
        this.performers = performers;
    }

    /**
     * The events the asking user could send to a target right now.
     *
     * @param target the target, resolved through the asking user's own session
     * @param login opens the engine's own session, when the asking resolver does not hold one yet
     * @return the event names, in alphabetical order
     * @throws WorkflowException when the engine's session cannot be opened, the user cannot be looked up, or several
     *             workflows would take one of the events
     */
    Set<String> availableEvents(final Resource target, final ServiceLogin login) throws WorkflowException
    {
        final Scope scope = Scope.of(target.getResourceResolver(), login, this.performers);
        final Resource privileged = scope.privileged(target);
        if (privileged.isResourceType(TaskInstance.RESOURCE_TYPE)) {
            return TaskCompletion.availableEvents(privileged, scope.check());
        }
        final Set<String> events = new TreeSet<>(
            SystemWorkflowLocator.availableEvents(scope.resolver, privileged, this.evaluator, scope.check()));
        events.addAll(listWaitingMessages(privileged, scope.check()));
        return events;
    }

    /**
     * The task a message sent to a host is for. It is the one task open on a workflow running over the host that waits
     * for the message and admits the sender. An event whose name does not {@link WorkflowEvent#getMarkedMessage() mark it
     * as a message} is for the host's system workflows instead.
     *
     * @param serviceResolver the engine's own session
     * @param host the resource the event is aimed at, backed by the engine's own session
     * @param event the incoming event
     * @param actor the user who sent it
     * @return the task, backed by the engine's own session, or empty when the event is not a message
     * @throws NoApplicableWorkflowException when nothing on the host waits for the message
     * @throws InvalidStateException when several waiting tasks admit the sender, so the message must go to one of them
     * @throws WorkflowException when no waiting task admits the sender, or the sender cannot be looked up
     */
    Optional<Resource> findMessageRecipient(final ResourceResolver serviceResolver, final Resource host,
        final WorkflowEvent event, final String actor) throws WorkflowException
    {
        final String message = event.getMarkedMessage();
        if (message == null) {
            return Optional.empty();
        }
        final List<Wait> waiting = findWaits(host).stream()
            .filter(wait -> message.equals(wait.message()))
            .collect(Collectors.toList());
        if (waiting.isEmpty()) {
            throw new NoApplicableWorkflowException("Nothing on " + host.getPath() + " waits for the " + message
                + " message");
        }
        final PerformerCheck check = this.performers.createCheck(serviceResolver, actor);
        final List<Wait> selected = selectWaitsFor(check, waiting, host);
        if (selected.isEmpty()) {
            throw check.refusal();
        }
        if (selected.size() > 1) {
            throw new InvalidStateException("Several tasks on " + host.getPath() + " wait for the " + message
                + " message from you, so send it to one of them: " + listPaths(selected));
        }
        return Optional.of(selected.get(0).task());
    }

    /**
     * The messages the asking user could send to a host. Each is one a task there waits for, offered only where sending
     * it to the host would find exactly one task for them.
     *
     * @param host the host, backed by the engine's own session
     * @param check whom the definitions admit, for the asking user
     * @return the marked message names
     * @throws WorkflowFailedException when the user's group membership cannot be read
     */
    private static Set<String> listWaitingMessages(final Resource host, final PerformerCheck check)
        throws WorkflowFailedException
    {
        final Map<String, List<Wait>> byMessage = findWaits(host).stream()
            .collect(Collectors.groupingBy(Wait::message));
        final Set<String> messages = new TreeSet<>();
        for (final Map.Entry<String, List<Wait>> waiting : byMessage.entrySet()) {
            if (selectWaitsFor(check, waiting.getValue(), host).size() == 1) {
                messages.add(WorkflowEvent.MESSAGE_PREFIX + waiting.getKey());
            }
        }
        return messages;
    }

    /**
     * The tasks open on the workflows running over a host that wait for a message.
     *
     * @param host the resource the workflows run over, backed by the engine's own session
     * @return each such task and the catching event it was raised from
     */
    private static List<Wait> findWaits(final Resource host)
    {
        return Optional.ofNullable(host.getChild(WorkflowInstances.NODE_NAME))
            .map(container -> container.adaptTo(WorkflowInstances.class))
            .map(WorkflowInstances::getActiveInstances)
            .orElse(List.of())
            .stream()
            .flatMap(instance -> instance.getTaskInstances().stream())
            .filter(task -> TaskInstance.OPEN_STATUS.equals(task.getStatus()))
            .flatMap(task -> findWaitOf(host, task).stream())
            .collect(Collectors.toList());
    }

    private static Optional<Wait> findWaitOf(final Resource host, final TaskInstance task)
    {
        final FlowNode definition = task.getDefinition();
        if (!(definition instanceof IntermediateCatchingEvent)) {
            return Optional.empty();
        }
        return Optional.of(new Wait(Objects.requireNonNull(host.getResourceResolver().getResource(task.getPath()),
            "A task just found on the host is there"), (IntermediateCatchingEvent) definition));
    }

    /**
     * The waits that admit the actor. Where several admit them, only those whose performers name them count, if any
     * do. A task meant for an administrator is then still found among others that let any administrator through.
     *
     * @param check whom the definitions admit, for the actor
     * @param waits the tasks waiting for one message
     * @param host the resource the workflows run over, whose creator {@code @creator} names
     * @return the waits the message is for
     * @throws WorkflowFailedException when the actor's group membership cannot be read
     */
    private static List<Wait> selectWaitsFor(final PerformerCheck check, final List<Wait> waits, final Resource host)
        throws WorkflowFailedException
    {
        // Loops rather than streams: asking about a performer can fail with a checked exception
        final List<Wait> admitted = new ArrayList<>();
        for (final Wait wait : waits) {
            if (check.admits(wait.event(), host)) {
                admitted.add(wait);
            }
        }
        if (admitted.size() < 2) {
            return admitted;
        }
        final List<Wait> named = new ArrayList<>();
        for (final Wait wait : admitted) {
            if (check.names(wait.event(), host)) {
                named.add(wait);
            }
        }
        return named.isEmpty() ? admitted : named;
    }

    private static String listPaths(final List<Wait> waits)
    {
        return waits.stream().map(wait -> wait.task().getPath()).collect(Collectors.joining(", "));
    }

    /**
     * The system workflow that would handle an event from the asking user, as their own session reads it. Only the
     * answer is handed over to the user's session.
     *
     * @param target the target, resolved through the asking user's own session
     * @param event the event's name
     * @param login opens the engine's own session, when the asking resolver does not hold one yet
     * @return the workflow version, or {@code null} when no system workflow would take the event from the user
     * @throws WorkflowException when the engine's session cannot be opened, the user cannot be looked up, several
     *             workflows would take the event, or the user's session cannot read the one that would
     */
    WorkflowVersion applicableWorkflow(final Resource target, final String event, final ServiceLogin login)
        throws WorkflowException
    {
        final Scope scope = Scope.of(target.getResourceResolver(), login, this.performers);
        final Resource privileged = scope.privileged(target);
        if (privileged.isResourceType(TaskInstance.RESOURCE_TYPE)) {
            return null;
        }
        final StartEvent start;
        try {
            start = SystemWorkflowLocator.find(scope.resolver, privileged, new WorkflowEvent(event, Map.of()),
                this.evaluator);
        } catch (final NoApplicableWorkflowException e) {
            return null;
        }
        return scope.check().admits(start, privileged)
            ? handOver(target.getResourceResolver(), start) : null;
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

        /** Says who the names in a definition stand for. */
        private final Performers performers;

        /** Whom the definitions admit, looked up the first time a question needs it. */
        private PerformerCheck check;

        private Scope(final ResourceResolver resolver, final String actor, final Performers performers)
        {
            this.resolver = resolver;
            this.actor = actor;
            this.performers = performers;
        }

        /**
         * The scope a resolver holds for its user, opened the first time they ask something through it.
         *
         * @param asking the resolver the user asks through
         * @param login opens the engine's own session
         * @param performers says who the names in a definition stand for
         * @return the scope, kept in the resolver until it closes
         * @throws WorkflowFailedException when the engine's session cannot be opened
         */
        static Scope of(final ResourceResolver asking, final ServiceLogin login, final Performers performers)
            throws WorkflowFailedException
        {
            final String actor = UserIds.canonical(asking);
            // Keyed by user as well: a wrapper can report another user while sharing the map of the resolver it wraps
            final String key = Scope.class.getName() + '/' + actor;
            final Map<String, Object> kept = asking.getPropertyMap();
            final Object existing = kept.get(key);
            if (existing instanceof Scope) {
                return (Scope) existing;
            }
            final Scope scope = new Scope(login.open(), actor, performers);
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
        PerformerCheck check() throws WorkflowFailedException
        {
            if (this.check == null) {
                this.check = this.performers.createCheck(this.resolver, this.actor);
            }
            return this.check;
        }

        @Override
        public void close()
        {
            this.resolver.close();
        }
    }

    /**
     * A task waiting for a message, and the catching event it was raised from.
     *
     * @param task the open task, backed by the engine's own session
     * @param event the catching event, which names the message and who may send it
     * @version $Id$
     * @since 0.1.0
     */
    private record Wait(Resource task, IntermediateCatchingEvent event)
    {
        String message()
        {
            return this.event.getMessageName();
        }
    }
}
