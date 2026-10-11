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

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConditionDependencies}: what a subtree may be named by, which operands elsewhere in its entity
 * name it, and which part each such condition is on.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ConditionDependenciesTest
{
    private static final String UNSTRUCTURED = "nt:unstructured";

    private static final String AGE = "form/age";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private Session session;

    private Node study;

    private Node form;

    private Node age;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        // Registered by hand: on a clean build the manifest declaring them does not exist yet
        NodeTypeDefinitionScanner.get().register(this.session,
            List.of("SLING-INF/nodetypes/conditions.cnd", "SLING-INF/nodetypes/conditions-test.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.study = this.session.getRootNode().addNode("study", "test:Study");
        this.form = this.study.addNode("form", UNSTRUCTURED);
        this.age = referenceable(this.form, "age");
        condition(this.study.addNode("byPath", UNSTRUCTURED), "answer", AGE);
        condition(this.study.addNode("byIdentifier", UNSTRUCTURED), "answer", this.age.getIdentifier());
        condition(this.study.addNode("literal", UNSTRUCTURED), "literal", AGE);
        condition(this.form, "answer", AGE);
        this.session.save();
    }

    @Test
    void namesWhatASubtreeHoldsByIdentifierAndPath() throws RepositoryException
    {
        assertEquals(Map.of(this.age.getIdentifier(), this.age.getIdentifier(), AGE, this.age.getIdentifier()),
            ConditionDependencies.namesOf(this.form));
        // A whole entity has no names, and neither has a node outside any entity
        assertTrue(ConditionDependencies.namesOf(this.study).isEmpty());
        assertTrue(ConditionDependencies.namesOf(referenceable(this.session.getRootNode(), "stray")).isEmpty());
    }

    @Test
    void findsTheOperandsElsewhereThatNameIt() throws RepositoryException
    {
        final List<Node> naming =
            ConditionDependencies.operandsNaming(this.age, ConditionDependencies.namesOf(this.age).keySet());

        assertEquals(Set.of("/study/byPath/cond:condition/operandA", "/study/byIdentifier/cond:condition/operandA",
            "/study/form/cond:condition/operandA"), Set.copyOf(paths(naming)));
        // The form's own condition goes with it
        assertEquals(2, ConditionDependencies.operandsNaming(this.form,
            ConditionDependencies.namesOf(this.form).keySet()).size());
        // Counting those that move along with it
        assertEquals(3, ConditionDependencies.operandsNaming(this.form,
            ConditionDependencies.namesOf(this.form).keySet(), true).size());
        assertTrue(ConditionDependencies.operandsNaming(this.age, Set.of()).isEmpty());
        assertEquals(List.of("/study/form/cond:condition/operandA"),
            paths(ConditionDependencies.operandsIn(this.form)));
        assertTrue(ConditionDependencies.operandsNaming(this.session.getRootNode(), Set.of(AGE)).isEmpty());
    }

    @Test
    void findsThePartAConditionIsOn() throws RepositoryException
    {
        final Node operand = this.session.getNode("/study/byPath/cond:condition/operandA");

        assertEquals("/study/byPath", ConditionDependencies.conditionedBy(operand).getPath());
        assertEquals(List.of(AGE), ConditionDependencies.names(operand));
        assertTrue(ConditionDependencies.isConditionPart("cond:SingleCondition"));
        assertFalse(ConditionDependencies.isConditionPart("sch:Question"));
        assertEquals("/study", ConditionDependencies.entityOf(this.age).getPath());
        assertNull(ConditionDependencies.entityOf(this.session.getRootNode()));
    }

    private static void condition(final Node part, final String source, final String value)
        throws RepositoryException
    {
        final Node condition = part.addNode("cond:condition", "cond:SingleCondition");
        condition.setProperty("comparator", "is not empty");
        final Node operand = condition.addNode("operandA", "cond:ConditionOperand");
        operand.setProperty("source", source);
        operand.setProperty("value", new String[] { value });
    }

    private static Node referenceable(final Node parent, final String name) throws RepositoryException
    {
        final Node node = parent.addNode(name, UNSTRUCTURED);
        node.addMixin("mix:referenceable");
        return node;
    }

    private static List<String> paths(final List<Node> nodes) throws RepositoryException
    {
        final String[] paths = new String[nodes.size()];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = nodes.get(i).getPath();
        }
        return List.of(paths);
    }
}
