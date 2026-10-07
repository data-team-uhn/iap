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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.Gateway;
import io.uhndata.iap.workflows.models.InclusiveGateway;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.ParallelGateway;
import io.uhndata.iap.workflows.models.SequenceFlow;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.WorkflowInstance;

/**
 * Answers the questions the walk asks of a workflow graph: which nodes execution may pass through, which arcs leave
 * a node, and when a join lets a token through.
 *
 * <p>These answers read the definition and the conditions on its arcs, and write nothing. Moving and spending tokens
 * is the walk's business.</p>
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
     * Whether the walk may carry execution through a node without stopping. The walk handles end events and
     * activities before it asks. The passable nodes are a start event, a gateway, and a boundary event that has just
     * fired.
     *
     * <p>A free-standing catching event is not passable: it is reached by an arc and has to wait, and nothing can wake
     * it yet. Any other node, such as a throwing event, has no meaning for the engine and is refused.</p>
     *
     * @param node the node execution is standing on
     * @return {@code true} if the walk may carry on through it
     */
    boolean passable(final FlowNode node)
    {
        return node instanceof StartEvent || node instanceof Gateway || fired(node);
    }

    /**
     * Whether a node is a join that can hold a token until other branches arrive.
     *
     * <p>Parallel and inclusive gateways are joins, released by different rules: see {@link #releases}. An exclusive
     * merge is not: each token that arrives there passes straight through.</p>
     *
     * @param node the node execution is standing on
     * @return {@code true} if arriving there may mean waiting for the others
     */
    boolean synchronises(final FlowNode node)
    {
        return (node instanceof ParallelGateway || node instanceof InclusiveGateway)
            && node.getIncomingFlows().size() > 1;
    }

    /**
     * Whether a join has everything it was waiting for.
     *
     * <p>A parallel join waits for one token per incoming arc, since its fork took every branch. An inclusive join
     * cannot count, because nothing records how many branches its fork took. It releases when no other token in the
     * instance can still reach it.</p>
     *
     * <p>The answer comes from the graph and the tokens on it, not from a record of the fork. It stays right when the
     * process is re-entered, when a boundary event cuts a branch short, and when the instance resumes days later.</p>
     *
     * @param gateway the join a token is standing on
     * @param arrived how many tokens are standing on it
     * @param elsewhere where every other token in the instance has got to
     * @return {@code true} if the join may release
     */
    boolean releases(final FlowNode gateway, final int arrived, final List<FlowNode> elsewhere)
    {
        if (gateway instanceof ParallelGateway) {
            return arrived >= gateway.getIncomingFlows().size();
        }
        return elsewhere.stream().noneMatch(node -> canReach(node, gateway.getElementId()));
    }

    /**
     * Whether execution standing on one node could still arrive at another by following the graph.
     *
     * <p>Sequence flows and boundary events both count as ways onwards, since a deadline can move a token off a
     * task. Erring towards "yes" is safe: an inclusive join may wait longer than it needed to. Erring towards "no"
     * would release the join while a branch was still coming, and strand that branch's token on a join nothing looks
     * at again.</p>
     *
     * @param from where execution is
     * @param targetId the element identifier being asked about
     * @return {@code true} if the target is reachable from there
     */
    private static boolean canReach(final FlowNode from, final String targetId)
    {
        final Set<String> seen = new HashSet<>();
        final Deque<FlowNode> frontier = new ArrayDeque<>();
        frontier.add(from);
        while (!frontier.isEmpty()) {
            final FlowNode node = frontier.remove();
            if (targetId.equals(node.getElementId())) {
                return true;
            }
            if (seen.add(node.getElementId())) {
                node.getOutgoingFlows().stream()
                    .map(SequenceFlow::getTarget)
                    .filter(Objects::nonNull)
                    .forEach(frontier::add);
                frontier.addAll(node.getNestedNodes());
            }
        }
        return false;
    }

    /**
     * Where a node leads: every arc for a parallel gateway, the applicable ones for an inclusive gateway, the chosen
     * one for any other gateway, and the only one for everything else.
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
        final List<SequenceFlow> taken;
        if (node instanceof ParallelGateway) {
            taken = all((ParallelGateway) node, flows);
        } else if (node instanceof InclusiveGateway) {
            taken = some((InclusiveGateway) node, flows, instance);
        } else {
            taken = List.of(node instanceof Gateway ? choose((Gateway) node, flows, instance) : only(node, flows));
        }
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
     * The ways out of an inclusive gateway that apply: every arc whose condition holds, and every arc with no
     * condition. When nothing applies, the default arc is taken.
     *
     * @param gateway the gateway being left
     * @param flows its outgoing arcs
     * @param instance the running instance, which is what the conditions are asked about
     * @return the arcs to follow, never empty
     * @throws WorkflowDefinitionException when nothing applies and there is no default
     */
    private List<SequenceFlow> some(final InclusiveGateway gateway, final List<SequenceFlow> flows,
        final WorkflowInstance instance) throws WorkflowDefinitionException
    {
        final List<SequenceFlow> applicable = flows.stream()
            .filter(flow -> flow.getCondition() == null
                || this.conditions.isSatisfied(flow.getCondition(), instance))
            .toList();
        if (!applicable.isEmpty()) {
            return applicable;
        }
        return List.of(flows.stream().filter(SequenceFlow::isDefault).findFirst()
            .orElseThrow(() -> new WorkflowDefinitionException("No outgoing sequence flow of the inclusive gateway "
                + gateway.getPath() + " applies to " + instance.getPath() + ", and none is marked as the default")));
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
     * does. An arc with no condition is taken only if it is the default.
     *
     * <p>Conditions are evaluated against the instance, so a guard reads the instance's variables through the
     * {@code variable} operand source.</p>
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
     * <p>No arc points at an event attached to an activity, so execution stands on one only after the event has
     * happened. What remains is to leave down its own arc.</p>
     *
     * @param node the node the walk is standing on
     * @return {@code true} if this is a boundary event that has fired
     */
    private static boolean fired(final FlowNode node)
    {
        return node instanceof IntermediateCatchingEvent && ((IntermediateCatchingEvent) node).getActivity() != null;
    }
}
