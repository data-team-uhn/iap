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
package io.uhndata.iap.conditions.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Finds the conditions whose {@code answer} operands depend on content about to be removed or moved. An
 * {@code answer} operand names a question by its identifier, or by its path within the entity holding the operand.
 * These methods find the operands elsewhere in that entity that name something in the content, and the part each
 * such condition is on.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class AnswerDependencies
{
    /** The source of an operand naming a question whose answer it compares. */
    public static final String ANSWER_SOURCE = "answer";

    private static final String VALUE = "value";

    private static final String SOURCE = "source";

    private AnswerDependencies()
    {
        // Utility class
    }

    /**
     * Collect all the "names" that can be used as answer dependencies, question identifiers and relative paths, mapped
     * to the internal identifier of the question.
     *
     * @param subtree the root of the subtree
     * @return each name mapped to the identifier of the node it names; empty for a whole entity, and for a node
     *     outside any entity
     * @throws RepositoryException when the subtree cannot be read
     */
    @NotNull
    public static Map<String, String> namesOf(@NotNull final Node subtree) throws RepositoryException
    {
        final Node entity = entityOf(subtree);
        final Map<String, String> names = new HashMap<>();
        if (entity != null && !entity.isSame(subtree)) {
            collect(subtree, entity.getPath().length() + 1, names);
        }
        return names;
    }

    /**
     * The {@code answer} operands elsewhere in a subtree's entity, outside the subtree, that name something in it:
     * what would be left depending on it if it went.
     *
     * @param subtree the root of the subtree
     * @param names the names of what is in it, from {@link #namesOf}
     * @return the operands, none when there are no names
     * @throws RepositoryException when the entity cannot be read
     */
    @NotNull
    public static List<Node> operandsNaming(@NotNull final Node subtree, @NotNull final Set<String> names)
        throws RepositoryException
    {
        return operandsNaming(subtree, names, false);
    }

    /**
     * The {@code answer} operands in a subtree's entity that name something in it, those in the subtree itself
     * included when asked: what a path that changes would break.
     *
     * @param subtree the root of the subtree
     * @param names the names of what is in it, from {@link #namesOf}
     * @param inside whether operands in the subtree count too
     * @return the operands, none when there are no names
     * @throws RepositoryException when the entity cannot be searched
     */
    public static List<Node> operandsNaming(final Node subtree, final Set<String> names, final boolean inside)
        throws RepositoryException
    {
        final List<Node> naming = new ArrayList<>();
        final Node entity = entityOf(subtree);
        if (names.isEmpty() || entity == null) {
            return naming;
        }
        for (final Node operand : answerOperands(entity, inside ? null : subtree)) {
            if (names(operand).stream().anyMatch(names::contains)) {
                naming.add(operand);
            }
        }
        return naming;
    }

    /**
     * The {@code answer} operands in a subtree, such as those of the conditions moving with it.
     *
     * @param subtree the root of the subtree
     * @return the operands
     * @throws RepositoryException when the subtree cannot be read
     */
    public static List<Node> operandsIn(final Node subtree) throws RepositoryException
    {
        return answerOperands(subtree, null);
    }

    /**
     * The {@code answer} operands under one node, and not under another. The tree is walked rather than queried: a
     * query would miss what the session has added and not saved yet.
     *
     * @param under where to look
     * @param notUnder where not to, or {@code null} to look everywhere under the first
     * @return the operands that name something
     * @throws RepositoryException when the tree cannot be read
     */
    private static List<Node> answerOperands(final Node under, final Node notUnder) throws RepositoryException
    {
        final List<Node> operands = new ArrayList<>();
        collectOperands(under, notUnder, operands);
        return operands;
    }

    /**
     * Gathers the {@code answer} operands under a node that name something.
     *
     * @param node where to look
     * @param skipped a subtree to leave out, or {@code null}
     * @param operands where to add the operands found
     * @throws RepositoryException when the tree cannot be read
     */
    private static void collectOperands(final Node node, final Node skipped, final List<Node> operands)
        throws RepositoryException
    {
        if (skipped != null && node.isSame(skipped)) {
            return;
        }
        if (isAnswerOperand(node)) {
            operands.add(node);
        }
        for (final NodeIterator children = node.getNodes(); children.hasNext();) {
            collectOperands(children.nextNode(), skipped, operands);
        }
    }

    /**
     * Whether a node is an {@code answer} operand naming something.
     *
     * @param node a node
     * @return whether it is
     * @throws RepositoryException when it cannot be read
     */
    private static boolean isAnswerOperand(final Node node) throws RepositoryException
    {
        return node.isNodeType("cond:ConditionOperand") && node.hasProperty(VALUE) && node.hasProperty(SOURCE)
            && ANSWER_SOURCE.equals(node.getProperty(SOURCE).getString());
    }

    /**
     * The values in an operand.
     *
     * @param operand an operand
     * @return its values, as text
     * @throws RepositoryException when they cannot be read
     */
    @NotNull
    public static List<String> names(@NotNull final Node operand) throws RepositoryException
    {
        final List<String> names = new ArrayList<>();
        for (final Value value : operand.getProperty(VALUE).getValues()) {
            names.add(value.getString());
        }
        return names;
    }

    /**
     * Whether a node is a condition or part of a condition.
     *
     * @param primaryType the node's primary type
     * @return whether it is
     */
    public static boolean isConditionPart(@NotNull final String primaryType)
    {
        return primaryType.startsWith("cond:");
    }

    /**
     * The node a condition is on, found from any node of the condition.
     *
     * @param conditionPart a condition, or a group or an operand in one
     * @return the nearest ancestor that is not part of a condition
     * @throws RepositoryException when an ancestor cannot be read
     */
    @NotNull
    public static Node conditionedBy(@NotNull final Node conditionPart) throws RepositoryException
    {
        Node current = conditionPart;
        while (current.getDepth() > 0 && isConditionPart(current.getPrimaryNodeType().getName())) {
            current = current.getParent();
        }
        return current;
    }

    /**
     * The entity a node belongs to, such as the schema version holding a question.
     *
     * @param node a node
     * @return the nearest entity at or above it, or {@code null} if the starting node is outside an entity
     * @throws RepositoryException when an ancestor cannot be read
     */
    @Nullable
    public static Node entityOf(@NotNull final Node node) throws RepositoryException
    {
        Node current = node;
        while (!current.isNodeType("data:Entity")) {
            if (current.getDepth() == 0) {
                return null;
            }
            current = current.getParent();
        }
        return current;
    }

    /**
     * Gathers the names of all the referenceable nodes in a subtree. For each referenceable node, both its internal
     * identifier and its relative path inside the entity are returned.
     *
     * @param node the root of the subtree
     * @param entityPathLength how much of a path is the entity's
     * @param names where to add them
     * @throws RepositoryException when the subtree cannot be read
     */
    private static void collect(final Node node, final int entityPathLength, final Map<String, String> names)
        throws RepositoryException
    {
        if (node.isNodeType("mix:referenceable")) {
            names.put(node.getIdentifier(), node.getIdentifier());
            names.put(node.getPath().substring(entityPathLength), node.getIdentifier());
        }
        for (final NodeIterator children = node.getNodes(); children.hasNext();) {
            collect(children.nextNode(), entityPathLength, names);
        }
    }
}
