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
import javax.jcr.query.Query;

/**
 * What conditions depend on, for the modules that change what they depend on. An {@code answer} operand names a
 * question by its identifier, or by its path within the entity holding the operand; these find, for content about to
 * go or to move, the operands elsewhere in its entity that name something in it, and the part each such condition is
 * on.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ConditionDependencies
{
    /** The source of an operand naming a question whose answer it compares. */
    public static final String ANSWER_SOURCE = "answer";

    private static final String VALUE = "value";

    private ConditionDependencies()
    {
        // Utility class
    }

    /**
     * Every name an {@code answer} operand may give the referenceable nodes of a subtree: each one's identifier, and
     * its path within its entity.
     *
     * @param subtree the root of the subtree
     * @return each name mapped to the identifier of the node it names; none outside any entity, or for an entity as
     *     a whole, since what names it from inside names it relative to it
     * @throws RepositoryException when the subtree cannot be read
     */
    public static Map<String, String> namesOf(final Node subtree) throws RepositoryException
    {
        final Node entity = entityOf(subtree);
        final Map<String, String> names = new HashMap<>();
        if (entity != null && !entity.isSame(subtree)) {
            collect(subtree, entity.getPath().length() + 1, names);
        }
        return names;
    }

    /**
     * The {@code answer} operands elsewhere in a subtree's entity, outside the subtree, that name something in it.
     *
     * @param subtree the root of the subtree
     * @param names the names of what is in it, from {@link #namesOf}
     * @return the operands, none when there are no names
     * @throws RepositoryException when the entity cannot be searched
     */
    public static List<Node> operandsNaming(final Node subtree, final Set<String> names) throws RepositoryException
    {
        final List<Node> naming = new ArrayList<>();
        final Node entity = entityOf(subtree);
        if (names.isEmpty() || entity == null) {
            return naming;
        }
        final NodeIterator operands = subtree.getSession().getWorkspace().getQueryManager().createQuery(
            "SELECT * FROM [cond:ConditionOperand] AS o WHERE ISDESCENDANTNODE(o, '" + quoted(entity.getPath())
                + "') AND NOT ISDESCENDANTNODE(o, '" + quoted(subtree.getPath()) + "') AND o.[source] = '"
                + ANSWER_SOURCE + "'",
            Query.JCR_SQL2).execute().getNodes();
        while (operands.hasNext()) {
            final Node operand = operands.nextNode();
            if (operand.hasProperty(VALUE) && names(operand).stream().anyMatch(names::contains)) {
                naming.add(operand);
            }
        }
        return naming;
    }

    /**
     * The values an operand names things by.
     *
     * @param operand an operand
     * @return its values, as text
     * @throws RepositoryException when they cannot be read
     */
    public static List<String> names(final Node operand) throws RepositoryException
    {
        final List<String> names = new ArrayList<>();
        for (final Value value : operand.getProperty(VALUE).getValues()) {
            names.add(value.getString());
        }
        return names;
    }

    /**
     * Whether a node is part of a condition, rather than of what the condition is on: the condition itself, its
     * groups and its operands.
     *
     * @param primaryType the node's primary type
     * @return whether it is
     */
    public static boolean isConditionPart(final String primaryType)
    {
        return primaryType.startsWith("cond:");
    }

    /**
     * What a part of a condition is the condition of.
     *
     * @param conditionPart a condition, or a group or an operand in one
     * @return the nearest ancestor that is not part of a condition
     * @throws RepositoryException when an ancestor cannot be read
     */
    public static Node conditionedBy(final Node conditionPart) throws RepositoryException
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
     * @return the nearest entity at or above it, or {@code null} outside any
     * @throws RepositoryException when an ancestor cannot be read
     */
    public static Node entityOf(final Node node) throws RepositoryException
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
     * Gathers the names of the referenceable nodes in a subtree.
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

    /**
     * A text as it goes in a quoted query literal.
     *
     * @param text any text
     * @return the text, its quotes doubled
     */
    private static String quoted(final String text)
    {
        return text.replace("'", "''");
    }
}
