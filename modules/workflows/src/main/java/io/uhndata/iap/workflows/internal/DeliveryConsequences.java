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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.api.RecordedEffect;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Event;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.spi.Execution;

/**
 * What one delivery leaves behind besides its writes. What its steps said they did, and the instance it started, are
 * recorded by its commit. The notifications its message events announced are sent once it commits, and the nodes its
 * steps checked out are checked back in.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DeliveryConsequences
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryConsequences.class);

    private final PendingNotifications notifications;

    private final List<RecordedEffect> effects = new ArrayList<>();

    private final Set<String> toCheckIn = new LinkedHashSet<>();

    private String startedInstance;

    /**
     * Constructor.
     *
     * @param notifications where the delivery's notifications wait for its commit
     */
    DeliveryConsequences(final PendingNotifications notifications)
    {
        this.notifications = notifications;
    }

    /**
     * Holds the notification a throwing event announces, until the delivery commits. It is about what the event
     * {@link Event#actsOn acts on}.
     *
     * @param source the throwing event
     * @param target the resource the run is aimed at, backed by the engine's own session
     * @param variables the run's variables, which say what it created
     * @param cause the event being delivered
     * @param actor the user the run acts for
     * @throws WorkflowDefinitionException when the event names an unknown subject
     */
    void hold(final Event source, final Resource target, final Map<String, Object> variables,
        final WorkflowEvent cause, final String actor) throws WorkflowDefinitionException
    {
        this.notifications.hold(source, source.actsOn(new Run(target, variables)), cause, actor);
    }

    /**
     * Notes what a step did to a resource.
     *
     * @param path the path of the resource
     * @param role the part it played, named by its purpose
     */
    void recordRole(final String path, final String role)
    {
        this.effects.add(new RecordedEffect(path, role));
    }

    /**
     * Notes an instance a step started. The first one started is the one the history names.
     *
     * @param path the path of the instance
     */
    void noteStartedInstance(final String path)
    {
        if (this.startedInstance == null) {
            this.startedInstance = path;
        }
    }

    /**
     * Notes a node to check back in once the delivery has committed.
     *
     * @param path the path of the versionable node
     */
    void checkInAfterCommit(final String path)
    {
        this.toCheckIn.add(path);
    }

    /**
     * The delivery as its history records it, when a system workflow handled its event.
     *
     * @param actor the user who sent the event
     * @param event the event
     * @param start the start event of the system workflow that caught it
     * @return the action, with the instance the delivery started and what its steps said they did
     */
    RecordedAction recordedAsSystemWorkflow(final Actor actor, final WorkflowEvent event, final FlowNode start)
    {
        return withEffects(DeliveryCauses.ofSystemWorkflow(actor, event, start, this.startedInstance));
    }

    /**
     * The delivery as its history records it, when its event completed a task of a running instance.
     *
     * @param actor the user who sent the event
     * @param event the event
     * @param task the task the event completed, as the event left it
     * @return the action, with what its steps said they did
     */
    RecordedAction recordedAsTask(final Actor actor, final WorkflowEvent event, final Resource task)
    {
        return withEffects(DeliveryCauses.ofTask(actor, event, task));
    }

    private RecordedAction withEffects(final RecordedAction.Builder cause)
    {
        this.effects.forEach(cause::affecting);
        return cause.build();
    }

    /**
     * Finishes what waited for the commit: sends the notifications, and checks in the nodes the steps checked out.
     * The delivery has committed by then, so a node that cannot be checked in is reported rather than thrown.
     *
     * @param resolver the engine's resolver, which committed the delivery
     */
    void completeAfterCommit(final ResourceResolver resolver)
    {
        this.notifications.send();
        final Session session = resolver.adaptTo(Session.class);
        if (session != null) {
            this.toCheckIn.forEach(path -> checkIn(session, path));
        }
    }

    private static void checkIn(final Session session, final String path)
    {
        try {
            if (!session.nodeExists(path)) {
                return;
            }
            final VersionManager versions = session.getWorkspace().getVersionManager();
            if (versions.isCheckedOut(path)) {
                versions.checkin(path);
            }
        } catch (final RepositoryException e) {
            LOGGER.error("Could not check {} back in after its delivery committed: {}", path, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(DeliveryConsequences.class, "checkIn").about(path));
        }
    }

    /**
     * What a run is working on, for a step that has no task context of its own, such as a thrown message.
     *
     * @param target the resource the event is aimed at, backed by the engine's own session
     * @param variables the run's variables
     * @version $Id$
     * @since 0.1.0
     */
    private record Run(Resource target, Map<String, Object> variables) implements Execution
    {
        @Override
        public Resource getTarget()
        {
            return this.target;
        }

        @Override
        public Object getVariable(final String name)
        {
            return this.variables.get(name);
        }

        @Override
        public ResourceResolver getResourceResolver()
        {
            return this.target.getResourceResolver();
        }
    }
}
