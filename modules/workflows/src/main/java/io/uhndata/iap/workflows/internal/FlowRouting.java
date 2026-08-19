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

import java.util.List;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.Gateway;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.ParallelGateway;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.WorkflowInstance;

/**
 * The shape questions the walk asks of a workflow graph: which nodes execution may pass through at all, which arcs
 * a node is left by, and when a join lets a token through.
 *
 * <p>Separate from the walk that asks them because these are answers about the <em>definition</em> — they read the
 * graph and the conditions on its arcs, and write nothing. What follows from them, moving and spending tokens, is
 * the walk's business. Keeping the two apart is also what stops "how a parallel gateway differs from an exclusive
 * one" from being spread through a loop that is really about tokens.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class FlowRouting
{
    private final ConditionEvaluator conditions;

    /**
     * Constructor.
     *
     * @param conditions the evaluator a gateway's guards are asked of
     */
    FlowRouting(final ConditionEvaluator conditions)
    {
        this.conditions = conditions;
    }

    /**
     * Whether the walk knows how to carry execution through a node without stopping. End events and activities are
     * handled before this is asked; what is left is the nodes execution merely passes: a start event it began at, a
     * gateway it routes through, and a boundary event that has just fired.
     *
     * <p>A free-standing catching event is deliberately <em>not</em> passable: it is reached by an arc and has to
     * wait, and nothing can yet wake it. Anything else — a throwing event, say — is a node the engine has no
     * meaning for, and passing it silently would be worse than refusing it.</p>
     *
     * @param node the node execution is standing on
     * @return {@code true} if the walk may carry on through it
     */
    boolean passable(final FlowNode node)
    {
        return node instanceof StartEvent || node instanceof Gateway || fired(node);
    }

    /**
     * Whether a node is a join that holds tokens until every branch has arrived.
     *
     * <p>Only a parallel gateway does. An exclusive merge is not a synchronisation point at all — each token that
     * arrives passes straight through, which is what makes it the merge to use after a decision, exactly one branch
     * of which was ever taken.</p>
     *
     * @param node the node execution is standing on
     * @return {@code true} if arriving there means waiting for the others
     */
    boolean synchronises(final FlowNode node)
    {
        return node instanceof ParallelGateway && node.getIncomingFlows().size() > 1;
    }

    /**
     * How many branches a join is waiting for, which is how many arcs lead into it.
     *
     * @param node the join
     * @return the number of incoming arcs
     */
    int branches(final FlowNode node)
    {
        return node.getIncomingFlows().size();
    }

    /**
     * Where a node leads: every arc for a parallel gateway, the chosen one for any other gateway, the only one for
     * everything else.
     *
     * @param node the node being left
     * @param instance the running instance, consulted for what a gateway routes on
     * @return the nodes to carry on at, never empty
     * @throws WorkflowDefinitionException when an arc leads nowhere, or there is no way onwards
     */
    List<FlowNode> targets(final FlowNode node, final WorkflowInstance instance)
        throws WorkflowDefinitionException
    {
        final List<SequenceFlow> flows = node.getOutgoingFlows();
        final List<SequenceFlow> taken = node instanceof ParallelGateway
            ? all((ParallelGateway) node, flows)
            : List.of(node instanceof Gateway ? choose((Gateway) node, flows, instance) : only(node, flows));
        for (final SequenceFlow flow : taken) {
            if (flow.getTarget() == null) {
                throw new WorkflowDefinitionException("The sequence flow " + flow.getPath() + " points at "
                    + flow.getTargetRef() + ", which does not exist in this workflow");
            }
        }
        return taken.stream().map(SequenceFlow::getTarget).toList();
    }

    /**
     * Every way out of a parallel gateway, all of which are taken at once.
     *
     * <p>No conditions are asked, deliberately: a parallel gateway takes every branch by definition, so a condition
     * on one of its arcs is a statement about a different kind of gateway. Ignoring it silently would be worse than
     * refusing it, since the diagram would then say something the engine does not do.</p>
     *
     * @param gateway the gateway being left
     * @param flows its outgoing arcs
     * @return every arc
     * @throws WorkflowDefinitionException when it leads nowhere, or an arc carries a condition
     */
    private List<SequenceFlow> all(final ParallelGateway gateway, final List<SequenceFlow> flows)
        throws WorkflowDefinitionException
    {
        if (flows.isEmpty()) {
            throw new WorkflowDefinitionException(gateway.getPath() + " has no outgoing sequence flow to leave by");
        }
        if (flows.stream().anyMatch(flow -> flow.getCondition() != null)) {
            throw new WorkflowDefinitionException("An arc of the parallel gateway " + gateway.getPath()
                + " carries a condition, but a parallel gateway takes every branch regardless");
        }
        return flows;
    }

    /**
     * The single way out of an ordinary node.
     *
     * @param node the node being left
     * @param flows its outgoing arcs
     * @return the only arc
     * @throws WorkflowDefinitionException when there is not exactly one
     */
    private SequenceFlow only(final FlowNode node, final List<SequenceFlow> flows)
        throws WorkflowDefinitionException
    {
        if (flows.size() != 1) {
            throw new WorkflowDefinitionException(node.getPath() + " has " + flows.size()
                + " outgoing sequence flows instead of exactly one");
        }
        return flows.get(0);
    }

    /**
     * Picks a gateway's outgoing arc: the first whose condition holds, or the one marked as the default when none
     * does.
     *
     * <p>The condition is the ordinary structured one, evaluated by the conditions module against the
     * <em>instance</em> — so a guard reads what the execution knows, such as the {@code outcome} the last completed
     * task recorded, through the {@code variable} operand source. An arc with no condition at all holds trivially,
     * which is why the default arc is a separate flag rather than simply the unconditional one: a gateway needs a
     * way to say "otherwise" that does not depend on where in the list it sits.</p>
     *
     * @param gateway the gateway being passed
     * @param flows its outgoing arcs
     * @param instance the running instance, which is what the conditions are asked about
     * @return the arc to follow
     * @throws WorkflowDefinitionException when nothing matches and there is no default
     */
    private SequenceFlow choose(final Gateway gateway, final List<SequenceFlow> flows,
        final WorkflowInstance instance) throws WorkflowDefinitionException
    {
        return flows.stream()
            .filter(flow -> flow.getCondition() != null
                && this.conditions.isSatisfied(flow.getCondition(), instance))
            .findFirst()
            .or(() -> flows.stream().filter(SequenceFlow::isDefault).findFirst())
            .orElseThrow(() -> new WorkflowDefinitionException("No outgoing sequence flow of " + gateway.getPath()
                + " has a condition that holds for " + instance.getPath() + ", and none is marked as the default"));
    }

    /**
     * Whether the walk is standing on a boundary event because it has just fired, rather than having arrived at
     * something it must wait for.
     *
     * <p>Position is what tells the two apart, and it is enough: an event attached to an activity is never reached
     * by an arc — nothing points at it — so the only way execution can be standing there is that the event
     * happened, and what remains is to leave down its own arc.</p>
     *
     * @param node the node the walk is standing on
     * @return {@code true} if this is a boundary event that has fired
     */
    private static boolean fired(final FlowNode node)
    {
        return node instanceof IntermediateCatchingEvent && ((IntermediateCatchingEvent) node).getActivity() != null;
    }
}
