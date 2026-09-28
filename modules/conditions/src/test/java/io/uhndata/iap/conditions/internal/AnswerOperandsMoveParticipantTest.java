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

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link AnswerOperandsMoveParticipant}: a condition naming a moved question, or one under a moved
 * part, by its path names it by identifier instead, and nothing else changes.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerOperandsMoveParticipantTest
{
    private static final String UNSTRUCTURED = "nt:unstructured";

    private static final String AGE = "form/age";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final AnswerOperandsMoveParticipant participant = new AnswerOperandsMoveParticipant();

    private Session session;

    private Node study;

    private Node form;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        // This module's own types: on a clean build its manifest, which declares them, does not exist yet
        NodeTypeDefinitionScanner.get().register(this.session,
            List.of("SLING-INF/nodetypes/conditions.cnd", "SLING-INF/nodetypes/conditions-test.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.study = this.session.getRootNode().addNode("study", "test:Study");
        this.form = this.study.addNode("form", UNSTRUCTURED);
        referenceable(this.form, "age");
        referenceable(this.form, "name");
        this.form.addNode("plain", UNSTRUCTURED);
        condition("onAge", "answer", AGE);
        condition("onBoth", "answer", "form/name", AGE, "elsewhere");
        condition("literal", "literal", AGE);
        this.session.save();
    }

    @Test
    void namesAMovedQuestionByIdentifier() throws RepositoryException
    {
        this.participant.beforeMove(this.form.getNode("age"), "/study/age");

        final String age = this.form.getNode("age").getIdentifier();
        assertEquals(List.of(age), values("onAge"));
        assertEquals(List.of("form/name", age, "elsewhere"), values("onBoth"));
        // A literal that happens to look like its path is a literal
        assertEquals(List.of(AGE), values("literal"));
    }

    @Test
    void namesEverythingUnderAMovedPartByIdentifier() throws RepositoryException
    {
        this.participant.beforeMove(this.form, "/study/moved");

        assertEquals(List.of(this.form.getNode("name").getIdentifier(), this.form.getNode("age").getIdentifier(),
            "elsewhere"), values("onBoth"));
    }

    @Test
    void namesByIdentifierWhatAConditionMovingAlongNames() throws RepositoryException
    {
        final Node inside = this.form.addNode("inside", "cond:SingleCondition");
        inside.setProperty("comparator", "is not empty");
        final Node operand = inside.addNode("operandA", "cond:ConditionOperand");
        operand.setProperty("source", "answer");
        operand.setProperty("value", new String[] { AGE });
        this.session.save();

        this.participant.beforeMove(this.form, "/study/moved");

        assertEquals(this.form.getNode("age").getIdentifier(), operand.getProperty("value").getValues()[0].getString());
    }

    @Test
    void namesWhatItLeavesBehindByIdentifierWhenItLeavesItsEntity() throws RepositoryException
    {
        final Node section = this.form.addNode("section", UNSTRUCTURED);
        final Node inside = section.addNode("inside", "cond:SingleCondition");
        inside.setProperty("comparator", "is not empty");
        final Node operand = inside.addNode("operandA", "cond:ConditionOperand");
        operand.setProperty("source", "answer");
        operand.setProperty("value", new String[] { AGE, "nowhere", "form/plain" });
        this.session.getRootNode().addNode("other", "test:Study");
        this.session.save();

        // Within its entity, a path to what stays is still good
        this.participant.beforeMove(section, "/study/section");
        assertEquals(AGE, operand.getProperty("value").getValues()[0].getString());

        this.participant.beforeMove(section, "/other/section");
        assertEquals(List.of(this.form.getNode("age").getIdentifier(), "nowhere", "form/plain"),
            List.of(operand.getProperty("value").getValues()[0].getString(),
                operand.getProperty("value").getValues()[1].getString(),
                operand.getProperty("value").getValues()[2].getString()));
        // Out of any entity, likewise
        operand.setProperty("value", new String[] { AGE });
        this.participant.beforeMove(section, "/section");
        assertEquals(this.form.getNode("age").getIdentifier(), operand.getProperty("value").getValues()[0].getString());
    }

    @Test
    void leavesAloneWhatNoPathChangesFor() throws RepositoryException
    {
        this.participant.beforeMove(this.study, "/moved");
        this.participant.beforeMove(this.form.getNode("plain"), "/study/plain");
        final Node stray = referenceable(this.session.getRootNode(), "stray");
        this.session.save();
        this.participant.beforeMove(stray, "/strayed");

        assertEquals(List.of(AGE), values("onAge"));
        assertEquals(List.of("form/name", AGE, "elsewhere"), values("onBoth"));
    }

    private void condition(final String name, final String source, final String... value)
        throws RepositoryException
    {
        final Node condition = this.study.addNode(name, "cond:SingleCondition");
        condition.setProperty("comparator", "is not empty");
        final Node operand = condition.addNode("operandA", "cond:ConditionOperand");
        operand.setProperty("source", source);
        operand.setProperty("value", value);
    }

    private static Node referenceable(final Node parent, final String name) throws RepositoryException
    {
        final Node node = parent.addNode(name, UNSTRUCTURED);
        node.addMixin("mix:referenceable");
        return node;
    }

    private List<String> values(final String condition) throws RepositoryException
    {
        final Value[] values = this.study.getNode(condition + "/operandA").getProperty("value").getValues();
        final String[] strings = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            strings[i] = values[i].getString();
        }
        return List.of(strings);
    }
}
