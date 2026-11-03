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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.Event;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.IntermediateThrowingEvent;
import io.uhndata.iap.workflows.models.StartEvent;

/**
 * The runtime of system workflows: the ones that run straight through, inside the delivery that triggered them, and
 * leave no instance behind. {@link InstanceRunner} is the other half.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SystemWorkflowRunner
{
    /** Where the human an execution acted for is recorded, {@code jcr:createdBy} being the engine itself. */
    private static final String CREATED_BY_PROPERTY = "createdBy";

    private final ServiceTaskDispatcher dispatcher;

    private final FlowRouting routing;

    /**
     * Constructor.
     *
     * @param dispatcher performs the service tasks and throws the messages of the delivery the run belongs to
     * @param routing moves the run from node to node
     */
    SystemWorkflowRunner(final ServiceTaskDispatcher dispatcher, final FlowRouting routing)
    {
        this.dispatcher = dispatcher;
        this.routing = routing;
    }

    /**
     * Runs a system workflow to quiescence, without committing: node by node from the start event, performing each
     * service task and throwing each message, until an end event is reached. A system workflow has no instance, and
     * nowhere to wait. Any node that cannot be passed straight through means the definition is not a valid system
     * workflow.
     *
     * @param target the resource the event is aimed at, backed by the engine's own session
     * @param event the incoming event
     * @param start the entry point to run from
     * @param actor the user who fired the event
     * @param depth how many sent events deep this run is, 0 for the event that came in
     * @return the variables the run left behind
     * @throws WorkflowException when the run cannot complete, typed by whose fault that is
     * @throws PersistenceException when a write fails
     */
    Map<String, Object> run(final Resource target, final WorkflowEvent event, final StartEvent start,
        final String actor, final int depth) throws WorkflowException, PersistenceException
    {
        final ResourceResolver resolver = target.getResourceResolver();
        final Map<String, Object> variables = new LinkedHashMap<>();
        FlowNode node = start;
        for (int step = 0; step < WorkflowEngineImpl.MAX_STEPS; step++) {
            if (node instanceof EndEvent) {
                this.dispatcher.throwMessage((Event) node, target, event, actor, variables);
                return variables;
            }
            if (node instanceof Activity) {
                this.dispatcher.perform((Activity) node, new WorkflowTaskContextImpl(target, event, (Activity) node,
                    variables, actor, this.dispatcher, depth));
                // After each activity rather than at the end: a later activity may start a workflow on what this one
                // created, and @creator there reads createdBy
                recordActor(resolver, variables, actor);
            } else if (node instanceof IntermediateThrowingEvent) {
                this.dispatcher.throwMessage((Event) node, target, event, actor, variables);
            } else if (!(node instanceof StartEvent) || step > 0) {
                // A system workflow cannot contain this node: there is no persisted instance whose token
                // could rest here
                throw new WorkflowDefinitionException("A system workflow cannot wait, but " + node.getPath()
                    + " is not a straight-through node");
            }
            node = this.routing.followOnlyFlow(node);
        }
        throw new WorkflowDefinitionException("The workflow did not reach an end event within "
            + WorkflowEngineImpl.MAX_STEPS + " steps; its sequence flows probably form a cycle");
    }

    /**
     * Records who an execution acted for, on whatever it created. The write itself was the engine's, so
     * {@code jcr:createdBy} names the service user. Nothing else would remember the human. The audit trail, every
     * "things I raised" listing and {@code @creator} all read it.
     *
     * @param resolver the engine's session, still uncommitted
     * @param variables the execution's variables, consulted for what was created
     * @param actor the user who fired the event
     * @throws PersistenceException when the created node cannot be written to
     */
    private static void recordActor(final ResourceResolver resolver, final Map<String, Object> variables,
        final String actor) throws PersistenceException
    {
        final Object created = variables.get(WorkflowResult.CREATED_PATH_VARIABLE);
        if (!(created instanceof String)) {
            return;
        }
        final Resource resource = Objects.requireNonNull(resolver.getResource((String) created),
            "A handler reported creating something that is not there");
        Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class),
            "A node the engine just created is always modifiable").put(CREATED_BY_PROPERTY, actor);
    }
}
