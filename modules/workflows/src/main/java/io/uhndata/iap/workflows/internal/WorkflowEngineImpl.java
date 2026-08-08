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
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The engine, at its current size: it receives domain events and executes the matching <em>system</em> workflow —
 * straight-through, within the request, leaving no instance behind.
 *
 * <p>Everything happens through the engine's own service user: matching reads {@code /SystemWorkflows}, which
 * ordinary users cannot see, and execution writes content they hold no rights on either. Authorization therefore
 * cannot be left to the repository — it is decided here, before the first step, by asking the start event which
 * principals it admits. That is the point of the arrangement: what a user may do is what the workflows say, not
 * what an access control list on the data happens to allow, and there is no second way in to keep in agreement
 * with the first. Everything a run changes lands in a single commit at quiescence: an event either fully happened
 * or didn't happen at all.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = WorkflowEngine.class)
public class WorkflowEngineImpl implements WorkflowEngine
{
    /** The subservice name under which the engine's service user is mapped. */
    private static final String SUBSERVICE = "workflows";

    /** Where the human an execution acted for is recorded, {@code jcr:createdBy} being the engine itself. */
    private static final String CREATED_BY = "createdBy";

    /**
     * How many nodes a single execution may pass through before the engine declares the definition broken. Far
     * above anything a real straight-through workflow needs; only there so a definition whose arcs form a cycle
     * fails fast instead of spinning.
     */
    private static final int MAX_STEPS = 50;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    private volatile List<ServiceTaskHandler> handlers;

    @Override
    public WorkflowResult receiveEvent(final Resource target, final WorkflowEvent event) throws WorkflowException
    {
        // Taken from the incoming resource rather than passed in: the session that resolved it is the one that
        // authenticated, so it is the authority on who is asking
        final String actor = target.getResourceResolver().getUserID();
        try (ResourceResolver serviceResolver =
            this.resolverFactory.getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            // Re-resolved through the engine's session: from here on the run is privileged, and the caller's own
            // view of the target — which may be nothing but the bare node they were allowed to post to — is not
            // enough to work with
            final Resource privilegedTarget = Objects.requireNonNull(serviceResolver.getResource(target.getPath()),
                "A target the caller could reach is always visible to the engine");
            if (privilegedTarget.isResourceType(TaskInstance.RESOURCE_TYPE)) {
                return resume(privilegedTarget, event, actor);
            }
            final StartEvent start = SystemWorkflowLocator.find(serviceResolver, target, event);
            PerformerCheck.verify(serviceResolver, start, actor);
            return execute(privilegedTarget, event, start, actor);
        } catch (final LoginException e) {
            throw new WorkflowFailedException("The workflow engine's service user is not available", e);
        }
    }

    /**
     * Carries a running instance on from the user task the event was aimed at. The same one-commit rule as a
     * system workflow: the decision, the task's new state and everything that follows from it either all happen or
     * none of them do.
     *
     * @param task the task being completed, resolved through the engine's session
     * @param event the incoming event
     * @param actor the user completing it
     * @return an empty result: completing a task creates nothing to send the caller to
     * @throws WorkflowException when the run cannot complete, typed by whose fault that is
     */
    private WorkflowResult resume(final Resource task, final WorkflowEvent event, final String actor)
        throws WorkflowException
    {
        final ResourceResolver resolver = task.getResourceResolver();
        try {
            TaskCompletion.apply(resolver, task, event, actor, performer(event, actor));
            resolver.commit();
            return new WorkflowResult(Map.of());
        } catch (final PersistenceException e) {
            revert(resolver);
            throw RepositoryFailures.translate(e);
        } catch (final WorkflowException | RuntimeException e) {
            revert(resolver);
            throw e;
        }
    }

    /**
     * How an instance performs a service task it meets: through the same dispatch a system workflow uses, so a
     * handler behaves identically whichever kind of workflow reached it. The variables are this delivery's alone —
     * an instance's persisted variables are not yet exposed to handlers.
     *
     * @param event the event being delivered
     * @param actor the user the instance is being moved for
     * @return a performer bound to this delivery
     */
    private InstanceRunner.ServiceTaskPerformer performer(final WorkflowEvent event, final String actor)
    {
        final Map<String, Object> variables = new LinkedHashMap<>();
        return (activity, instance) -> perform(activity,
            new WorkflowTaskContextImpl(hostOf(instance), event, activity, variables, actor));
    }

    /**
     * The resource an instance drives, two levels up past its container.
     *
     * @param instance a running instance
     * @return the host resource
     */
    private Resource hostOf(final Resource instance)
    {
        return Objects.requireNonNull(Objects.requireNonNull(instance.getParent(),
            "An instance always lives in a container").getParent(), "A container always lives in its host");
    }

    /**
     * Runs the matched workflow to quiescence: node by node from the start event, performing each service task,
     * until an end event is reached and everything is committed at once. System workflows have no instance and
     * therefore nowhere to wait, so any node that cannot be passed straight through means the definition is not a
     * valid system workflow.
     *
     * @param target the resource the event is aimed at, backed by the engine's own session
     * @param event the incoming event
     * @param start the entry point to run from
     * @param actor the user who fired the event
     * @return the variables the execution left behind
     * @throws WorkflowException when the run cannot complete, typed by whose fault that is
     */
    private WorkflowResult execute(final Resource target, final WorkflowEvent event, final StartEvent start,
        final String actor) throws WorkflowException
    {
        final ResourceResolver resolver = target.getResourceResolver();
        final Map<String, Object> variables = new LinkedHashMap<>();
        try {
            FlowNode node = start;
            for (int step = 0; step < MAX_STEPS; step++) {
                if (node instanceof EndEvent) {
                    recordActor(resolver, variables, actor);
                    resolver.commit();
                    return new WorkflowResult(variables);
                }
                if (node instanceof Activity) {
                    perform((Activity) node,
                        new WorkflowTaskContextImpl(target, event, (Activity) node, variables, actor));
                } else if (!(node instanceof StartEvent) || step > 0) {
                    // Not somewhere execution can pass straight through, so a system workflow cannot contain it:
                    // there is no persisted instance whose token could rest here
                    throw new WorkflowDefinitionException("A system workflow cannot wait, but " + node.getPath()
                        + " is not a straight-through node");
                }
                node = advance(node);
            }
            throw new WorkflowDefinitionException("The workflow did not reach an end event within " + MAX_STEPS
                + " steps; its sequence flows probably form a cycle");
        } catch (final PersistenceException e) {
            revert(resolver);
            throw RepositoryFailures.translate(e);
        } catch (final WorkflowException | RuntimeException e) {
            revert(resolver);
            throw e;
        }
    }

    /**
     * Records who an execution acted for, on whatever it created. The write itself was the engine's, so
     * {@code jcr:createdBy} names the service user and nothing in the repository would otherwise remember the
     * human — which both the audit trail and every "things I raised" listing depend on.
     *
     * @param resolver the engine's session, still uncommitted
     * @param variables the execution's variables, consulted for what was created
     * @param actor the user who fired the event
     * @throws PersistenceException when the created node cannot be written to
     */
    private void recordActor(final ResourceResolver resolver, final Map<String, Object> variables,
        final String actor) throws PersistenceException
    {
        final Object created = variables.get(WorkflowResult.CREATED_PATH);
        if (!(created instanceof String)) {
            return;
        }
        final Resource resource = Objects.requireNonNull(resolver.getResource((String) created),
            "A handler reported creating something that is not there");
        Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class),
            "A node the engine just created is always modifiable").put(CREATED_BY, actor);
    }

    /**
     * Performs one service task by dispatching to the handler its activity names.
     *
     * @param activity the activity node being executed
     * @param context what the handler gets to work with
     * @throws WorkflowException when the activity cannot be performed
     * @throws PersistenceException when the handler's repository writes fail immediately
     */
    private void perform(final Activity activity, final WorkflowTaskContext context)
        throws WorkflowException, PersistenceException
    {
        final String name = activity.getHandler();
        if (name == null) {
            throw new WorkflowDefinitionException("A system workflow cannot wait, but the activity "
                + activity.getPath() + " names no handler to perform it automatically");
        }
        if (WorkflowStarter.NAME.equals(name)) {
            // Built into the engine rather than registered: putting an entity under a workflow is the engine's own
            // business, even though which entities get one stays a matter of content
            WorkflowStarter.execute(context, performer(context.getEvent(), context.getActor()));
            return;
        }
        final ServiceTaskHandler handler = this.handlers.stream()
            .filter(candidate -> name.equals(candidate.getName()))
            .findFirst()
            .orElse(null);
        if (handler == null) {
            throw new WorkflowDefinitionException(
                "The activity " + activity.getPath() + " names the handler " + name + ", but none is registered");
        }
        handler.execute(context);
    }

    /**
     * Follows the single outgoing arc of a straight-through node.
     *
     * @param node the node execution is leaving
     * @return the node the arc leads to
     * @throws WorkflowDefinitionException when the node does not have exactly one resolvable way out
     */
    private FlowNode advance(final FlowNode node) throws WorkflowDefinitionException
    {
        final List<SequenceFlow> flows = node.getOutgoingFlows();
        if (flows.size() != 1) {
            throw new WorkflowDefinitionException("A system workflow must be straight-through, but " + node.getPath()
                + " has " + flows.size() + " outgoing sequence flows instead of exactly one");
        }
        final FlowNode next = flows.get(0).getTarget();
        if (next == null) {
            throw new WorkflowDefinitionException("The sequence flow " + flows.get(0).getPath()
                + " points at " + flows.get(0).getTargetRef() + ", which does not exist in this workflow");
        }
        return next;
    }

    /**
     * Discards whatever an aborted execution had already changed, so the failing event leaves no trace in the
     * firing user's session.
     *
     * @param resolver the firing user's resource resolver
     */
    private void revert(final ResourceResolver resolver)
    {
        if (resolver.hasChanges()) {
            resolver.revert();
        }
    }
}
