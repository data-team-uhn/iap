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
     * Whether a node is a join that can hold a token until other branches arrive.
     *
     * <p>A parallel and an inclusive gateway both do, by different rules — see {@link #releases}. An exclusive merge
     * is not a synchronisation point at all: each token that arrives passes straight through, which is what makes it
     * the merge to use after a decision, exactly one branch of which was ever taken.</p>
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
     * <p>A parallel join counts: it takes one token per incoming arc, because a parallel fork took every branch and
     * every branch therefore owes it one. An inclusive join cannot count — its fork took only the branches that
     * applied, and how many that was is not written anywhere — so it asks the question that actually matters
     * instead: <em>can any branch still get here?</em> When none of the tokens elsewhere in the instance can reach
     * it, whatever arrived is all that ever will, and the join releases.</p>
     *
     * <p>Asking about reachability rather than remembering the fork is what makes this survive the process being
     * re-entered, a branch being cut short by a boundary event, or the instance being resumed days later by somebody
     * else: the answer is read from the graph and the tokens on it, which is all there is to go on.</p>
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
     * <p>Boundary events count as ways onwards as well as sequence flows: a token waiting on a task can be taken
     * away from it by a deadline, and where that leads is somewhere it can still get to. Erring towards "yes" is
     * the safe direction — it makes an inclusive join wait when it might not have needed to, where erring the other
     * way would release it while a branch was still coming and leave that branch's token stranded on a join
     * nothing would look at again.</p>
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
     * The ways out of an inclusive gateway that apply: every arc whose condition holds, and every arc that carries
     * no condition at all, since an arc that asks nothing is always taken.
     *
     * <p>Falls back on the default arc when nothing applies, the same way an exclusive gateway does — "otherwise"
     * means the same thing whether one branch is being chosen or several.</p>
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
