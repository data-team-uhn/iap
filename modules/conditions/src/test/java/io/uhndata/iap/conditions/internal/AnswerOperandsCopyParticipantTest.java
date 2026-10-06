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
package io.uhndata.iap.conditions.internal;

import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Unit tests for {@link AnswerOperandsCopyParticipant}: once copied, an answer operand naming a copied question by
 * UUID names its copy, a path is kept as written, and anything else is left as it was.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerOperandsCopyParticipantTest
{
    private static final String UNSTRUCTURED = "nt:unstructured";

    private static final String ANSWER = "answer";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    void pointsUuidsAtTheCopiedQuestionsAndKeepsPaths() throws RepositoryException
    {
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        // This module's own types: on a clean build its manifest, which declares them, does not exist yet
        NodeTypeDefinitionScanner.get().register(session, List.of("SLING-INF/nodetypes/conditions.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        final Node source = tree(session.getRootNode(), "source", "q");
        final String original = source.getNode("q").getIdentifier();
        final Node copy = tree(session.getRootNode(), "copy", original);
        session.save();

        new AnswerOperandsCopyParticipant().afterCopy(source, copy,
            Map.of(original, copy.getNode("q").getIdentifier()));

        final String copied = copy.getNode("q").getIdentifier();
        // A path stays readable, and the copy's own structure is what it resolves against
        assertArrayEquals(new String[] { "q" }, values(copy.getNode("byPath/operandA")));
        assertArrayEquals(new String[] { copied, "nowhere", "/abs" }, values(copy.getNode("byPath/operandB")));
        // A literal that happens to look like a question's path is a literal
        assertArrayEquals(new String[] { "q" }, values(copy.getNode("literal/operandA")));
    }

    /**
     * A question, a condition naming it by path and then by UUID or name, and a literal condition.
     *
     * @param parent where the tree goes
     * @param name the tree's name
     * @param byUuid how the second operand names the question
     * @return the tree
     * @throws RepositoryException when it cannot be written
     */
    private static Node tree(final Node parent, final String name, final String byUuid) throws RepositoryException
    {
        final Node tree = parent.addNode(name, UNSTRUCTURED);
        tree.addNode("q", UNSTRUCTURED).addMixin("mix:referenceable");
        final Node byPath = condition(tree, "byPath", ANSWER, "q");
        byPath.getNode("operandB").setProperty("source", ANSWER);
        byPath.getNode("operandB").setProperty("value", new String[] { byUuid, "nowhere", "/abs" });
        condition(tree, "literal", "literal", "q");
        return tree;
    }

    private static Node condition(final Node parent, final String name, final String source, final String value)
        throws RepositoryException
    {
        final Node condition = parent.addNode(name, "cond:SingleCondition");
        condition.setProperty("comparator", "equals");
        final Node operand = condition.addNode("operandA", "cond:ConditionOperand");
        operand.setProperty("source", source);
        operand.setProperty("value", new String[] { value });
        return condition;
    }

    private static String[] values(final Node operand) throws RepositoryException
    {
        final Value[] values = operand.getProperty("value").getValues();
        final String[] strings = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            strings[i] = values[i].getString();
        }
        return strings;
    }
}
