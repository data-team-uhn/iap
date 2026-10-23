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
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

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
import io.uhndata.iap.workflows.models.WorkflowToken;

/**
 * Answers questions about a workflow graph: which nodes a token may pass through, which outgoing flows a node would
 * take, and whether a join would let a token through.
 *
 * <p>Every answer reads the definition, the conditions on its arcs, and the tokens it is shown. Nothing here moves
 * or removes a token: the engine does that with the answers.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = FlowRouting.class)
public class FlowRouting
{
    private final ConditionEvaluator conditionEvaluator;

    /**
     * Constructor.
     *
     * @param conditionEvaluator the evaluator a gateway's guards are asked of
     */
    @Activate
    public FlowRouting(@Reference final ConditionEvaluator conditionEvaluator)
    {
        this.conditionEvaluator = conditionEvaluator;
    }

    /**
     * Whether a token may pass through a node without stopping there. A start event and a gateway may be passed, and
     * so may a boundary event a token stands on, since it stands there only once the event has fired. End events and
     * activities are not asked about: the engine handles them first.
     *
     * <p>A free-standing catching event may not be passed: a token reaching it would have to wait, and nothing can
     * wake it yet. Any other node, such as a throwing event, means nothing to the engine and may not be passed
     * either.</p>
     *
     * @param node the node to ask about
     * @return {@code true} if a token may pass through it
     */
    boolean canPassThrough(final FlowNode node)
    {
        return node instanceof StartEvent || node instanceof Gateway || isFiredBoundaryEvent(node);
    }

    /**
     * Whether a node is a join, where a token may have to wait for tokens from other branches.
     *
     * <p>Parallel and inclusive gateways with more than one incoming flow are joins, each released by its own rule:
     * see {@link #canReleaseJoin}. An exclusive merge is not a join: every token arriving there passes straight
     * through.</p>
     *
     * @param node the node to ask about
     * @return {@code true} if a token arriving there may have to wait
     */
    boolean isSynchronizingJoin(final FlowNode node)
    {
        return (node instanceof ParallelGateway || node instanceof InclusiveGateway)
            && node.getIncomingFlows().size() > 1;
    }

    /**
     * Whether a join would let a token through, given the tokens standing on it and those elsewhere in the instance.
     *
     * <p>A parallel join needs a token from every incoming flow, since its fork took every branch. It counts flows,
     * not tokens: a second token down a flow that already delivered one waits for the join's next round. An inclusive
     * join cannot count, because nothing records how many branches its fork took. It lets a token through once no
     * other token in the instance can still reach it.</p>
     *
     * <p>The answer depends only on the graph and the tokens on it, not on a record of the fork. It stays right when
     * a boundary event cuts a branch short, and when the instance resumes days later.</p>
     *
     * @param join the join to ask about
     * @param arrivedBy the incoming flows the tokens standing on the join arrived by
     * @param otherTokenPositions the nodes every other token in the instance stands on
     * @return {@code true} if the join would let a token through
     */
    boolean canReleaseJoin(final FlowNode join, final Collection<SequenceFlow> arrivedBy,
        final List<FlowNode> otherTokenPositions)
    {
        if (join instanceof ParallelGateway) {
            final Set<String> arrived = new HashSet<>();
            arrivedBy.forEach(flow -> arrived.add(flow.getElementId()));
            return join.getIncomingFlows().stream().map(SequenceFlow::getElementId).allMatch(arrived::contains);
        }
        return otherTokenPositions.stream().noneMatch(node -> canStillReach(node, join.getElementId()));
    }

    /**
     * Which of the tokens waiting on a join would merge into a token arriving there, if the join lets it through.
     * The arriving token is the one that would carry on past the join; the tokens returned would end there.
     *
     * <p>An inclusive join would merge every waiting token. A parallel join would merge one waiting token for each of
     * its other incoming flows, leaving any extra token from a flow to wait for the next round.</p>
     *
     * @param join the join the token arrives at
     * @param arrivingTokenPath the path of the arriving token
     * @param arrivingFlow the incoming flow the token arrives by
     * @param waiting every token standing on the join, which may include the arriving one
     * @return the waiting tokens that would merge into the arriving one
     */
    List<WorkflowToken> findTokensMergingIntoArrival(final FlowNode join, final String arrivingTokenPath,
        final SequenceFlow arrivingFlow, final List<WorkflowToken> waiting)
    {
        final List<WorkflowToken> others = waiting.stream()
            .filter(token -> !token.getPath().equals(arrivingTokenPath))
            .toList();
        if (!(join instanceof ParallelGateway)) {
            return others;
        }
        final Set<String> flows = new HashSet<>();
        flows.add(Objects.requireNonNull(arrivingFlow, "A token reaches a join along one of its incoming flows")
            .getElementId());
        return others.stream().filter(token -> flows.add(token.getArrivedBy())).toList();
    }

    /**
     * Whether a token standing on one node could still arrive at another by following the graph.
     *
     * <p>Sequence flows and boundary events both count as ways onwards, since a deadline can move a token off a
     * task. Erring towards "yes" is safe: an inclusive join may wait longer than it needed to. Erring towards "no"
     * would release the join while a branch was still coming, and strand that branch's token on a join nothing looks
     * at again.</p>
     *
     * @param from where the token stands
     * @param targetId the element identifier being asked about
     * @return {@code true} if the target is reachable from there
     */
    private static boolean canStillReach(final FlowNode from, final String targetId)
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
     * Which outgoing flows a token leaving a node would take: every one for a parallel gateway, the applicable ones
     * for an inclusive gateway, the chosen one for any other gateway, and the only one for every other node.
     *
     * @param node the node being left
     * @param instance the running instance, which a gateway's conditions are asked about
     * @return the flows to take, never empty, each leading to a node of the workflow
     * @throws WorkflowDefinitionException when a flow leads nowhere, or there is no way onwards
     */
    List<SequenceFlow> selectOutgoingFlows(final FlowNode node, final WorkflowInstance instance)
        throws WorkflowDefinitionException
    {
        final List<SequenceFlow> taken;
        if (node instanceof ParallelGateway) {
            taken = selectEveryFlow((ParallelGateway) node);
        } else if (node instanceof InclusiveGateway) {
            taken = selectApplicableFlows((InclusiveGateway) node, instance);
        } else {
            taken = List.of(node instanceof Gateway ? chooseFlow((Gateway) node, instance) : selectOnlyFlow(node));
        }
        for (final SequenceFlow flow : taken) {
            getTarget(flow);
        }
        return taken;
    }

    /**
     * The node a token leaving a node that is not a gateway would reach, along its only outgoing flow. A system
     * workflow runs straight through, so this is every step it takes.
     *
     * @param node the node being left
     * @return the node its only outgoing flow leads to
     * @throws WorkflowDefinitionException when the node does not have exactly one outgoing flow, or it leads nowhere
     */
    FlowNode followOnlyFlow(final FlowNode node) throws WorkflowDefinitionException
    {
        return getTarget(selectOnlyFlow(node));
    }

    /**
     * The node a flow leads to.
     *
     * @param flow the flow to follow
     * @return the node it points at
     * @throws WorkflowDefinitionException when it points at nothing in the workflow
     */
    private static FlowNode getTarget(final SequenceFlow flow) throws WorkflowDefinitionException
    {
        final FlowNode target = flow.getTarget();
        if (target == null) {
            throw new WorkflowDefinitionException("The sequence flow " + flow.getPath() + " points at "
                + flow.getTargetRef() + ", which does not exist in this workflow");
        }
        return target;
    }

    /**
     * Every outgoing flow of a parallel gateway, all of which are taken at once.
     *
     * @param gateway the gateway being left
     * @return every outgoing flow
     * @throws WorkflowDefinitionException when it has none, or one carries a condition
     */
    private List<SequenceFlow> selectEveryFlow(final ParallelGateway gateway)
        throws WorkflowDefinitionException
    {
        final List<SequenceFlow> flows = gateway.getOutgoingFlows();
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
     * The outgoing flows of an inclusive gateway that apply: every flow whose condition holds, and every other flow
     * with no condition. The default flow applies only when nothing else does.
     *
     * @param gateway the gateway being left
     * @param instance the running instance, which is what the conditions are asked about
     * @return the flows that apply, never empty
     * @throws WorkflowDefinitionException when nothing applies and there is no default
     */
    private List<SequenceFlow> selectApplicableFlows(final InclusiveGateway gateway, final WorkflowInstance instance)
        throws WorkflowDefinitionException
    {
        final List<SequenceFlow> flows = gateway.getOutgoingFlows();
        final List<SequenceFlow> applicable = flows.stream()
            .filter(flow -> !flow.isDefault())
            .filter(flow -> flow.getCondition() == null
                || this.conditionEvaluator.isSatisfied(flow.getCondition(), instance))
            .toList();
        if (!applicable.isEmpty()) {
            return applicable;
        }
        return List.of(flows.stream().filter(SequenceFlow::isDefault).findFirst()
            .orElseThrow(() -> new WorkflowDefinitionException("No outgoing sequence flow of the inclusive gateway "
                + gateway.getPath() + " applies to " + instance.getPath() + ", and none is marked as the default")));
    }

    /**
     * The single outgoing flow of a node that is not a gateway.
     *
     * @param node the node being left
     * @return its only outgoing flow
     * @throws WorkflowDefinitionException when there is not exactly one
     */
    private SequenceFlow selectOnlyFlow(final FlowNode node)
        throws WorkflowDefinitionException
    {
        final List<SequenceFlow> flows = node.getOutgoingFlows();
        if (flows.size() != 1) {
            throw new WorkflowDefinitionException(node.getPath() + " has " + flows.size()
                + " outgoing sequence flows instead of exactly one");
        }
        return flows.get(0);
    }

    /**
     * The outgoing flow an exclusive gateway would choose: the first whose condition holds, or the one marked as the
     * default when none does. A flow with no condition is chosen only if it is the default, or if it is the gateway's
     * only way out: that gateway is a merge, with nothing to choose.
     *
     * <p>Conditions are evaluated against the instance, so a guard reads the instance's variables through the
     * {@code variable} operand source.</p>
     *
     * @param gateway the gateway being left
     * @param instance the running instance, which is what the conditions are asked about
     * @return the flow to take
     * @throws WorkflowDefinitionException when nothing matches and there is no default
     */
    private SequenceFlow chooseFlow(final Gateway gateway, final WorkflowInstance instance)
        throws WorkflowDefinitionException
    {
        final List<SequenceFlow> flows = gateway.getOutgoingFlows();
        if (flows.size() == 1 && flows.get(0).getCondition() == null) {
            return flows.get(0);
        }
        return flows.stream()
            .filter(flow -> flow.getCondition() != null
                && this.conditionEvaluator.isSatisfied(flow.getCondition(), instance))
            .findFirst()
            .or(() -> flows.stream().filter(SequenceFlow::isDefault).findFirst())
            .orElseThrow(() -> new WorkflowDefinitionException("No outgoing sequence flow of " + gateway.getPath()
                + " has a condition that holds for " + instance.getPath() + ", and none is marked as the default"));
    }

    /**
     * Whether a node is a boundary event, which a token stands on only once the event has fired.
     *
     * <p>No arc points at an event attached to an activity, so a token reaches one only by the event firing. What
     * remains is to leave down its own arc.</p>
     *
     * @param node the node a token stands on
     * @return {@code true} if this is a boundary event
     */
    private static boolean isFiredBoundaryEvent(final FlowNode node)
    {
        return node instanceof IntermediateCatchingEvent && ((IntermediateCatchingEvent) node).getActivity() != null;
    }
}
