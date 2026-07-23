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

import java.util.Map;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AnswerOperandResolver}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerOperandResolverTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String ENTITY_TYPE = "iap/Entity";

    private static final String QUESTION_ID = "11111111-2222-3333-4444-555555555555";

    private final SlingContext context = new SlingContext();

    private final AnswerOperandResolver resolver = new AnswerOperandResolver();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionOperand.class);
    }

    /** Creates an operand identifying the target question by the given reference. */
    private ConditionOperand createOperand(final String... reference)
    {
        // The operand definition lives inside a schema-version-like entity, the base for relative path references
        this.context.create().resource("/Schemas/schema/1.0",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);
        return this.context.create().resource("/Schemas/schema/1.0/req/cond:condition/operandA", Map.of(
            SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE,
            "source", "answer",
            "value", reference)).adaptTo(ConditionOperand.class);
    }

    @Test
    void implementsTheAnswerSource()
    {
        assertEquals("answer", this.resolver.getSource());
    }

    @Test
    void resolvesAnswersByQuestionUuid()
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        final Resource submission = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);
        this.context.create().resource("/Submissions/sub/a1", Map.of(
            "question", QUESTION_ID, "value", new String[]{ "yes" }));

        final Operand resolved = this.resolver.resolve(operand, submission);

        assertEquals(1, resolved.size());
        assertEquals("yes", resolved.get(0));
        // Without a JCR session the question node cannot be loaded, so its type stays unknown
        assertNull(resolved.getType());
    }

    @Test
    void resolvesAnswersByQuestionPath()
    {
        final ConditionOperand operand = this.createOperand("form/q1");
        this.context.create().resource("/Schemas/schema/1.0/form/q1", Map.of(
            "jcr:uuid", QUESTION_ID, "dataType", "long"));
        final Resource submission = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);
        this.context.create().resource("/Submissions/sub/a1", Map.of(
            "question", QUESTION_ID, "value", new String[]{ "42" }));

        final Operand resolved = this.resolver.resolve(operand, submission);

        assertEquals(1, resolved.size());
        assertEquals("42", resolved.get(0));
        // The question's own declared data type steers the comparison type unification
        assertEquals(OperandType.LONG, resolved.getType());
    }

    @Test
    void uuidReferencesLoadTheQuestionTypeThroughTheSession()
        throws RepositoryException
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        this.context.create().resource("/Schemas/schema/1.0/form/q1", Map.of(
            "jcr:uuid", QUESTION_ID, "dataType", "long"));
        final Node questionNode = Mockito.mock(Node.class);
        Mockito.when(questionNode.getPath()).thenReturn("/Schemas/schema/1.0/form/q1");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(QUESTION_ID)).thenReturn(questionNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
        final Resource submission = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);
        this.context.create().resource("/Submissions/sub/a1", Map.of(
            "question", QUESTION_ID, "value", new String[]{ "42" }));

        final Operand resolved = this.resolver.resolve(operand, submission);

        assertEquals(OperandType.LONG, resolved.getType());
    }

    @Test
    void uuidReferencesTolerateUnresolvableIdentifiers()
        throws RepositoryException
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(QUESTION_ID)).thenThrow(new ItemNotFoundException());
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
        final Resource submission = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);
        this.context.create().resource("/Submissions/sub/a1", Map.of(
            "question", QUESTION_ID, "value", new String[]{ "yes" }));

        // The answer is still matched by the identifier itself; only the type stays unknown
        final Operand resolved = this.resolver.resolve(operand, submission);

        assertEquals("yes", resolved.get(0));
        assertNull(resolved.getType());
    }

    @Test
    void prefersTheAnswerClosestToTheContext()
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        this.context.create().resource("/Submissions/sub", SLING_RESOURCE_TYPE, ENTITY_TYPE);
        this.context.create().resource("/Submissions/sub/block1/a1", Map.of(
            "question", QUESTION_ID, "value", new String[]{ "one" }));
        final Resource block2 = this.context.create().resource("/Submissions/sub/block2");
        this.context.create().resource("/Submissions/sub/block2/a2", Map.of(
            "question", QUESTION_ID, "value", new String[]{ "two" }));

        // Evaluated inside the second repeated block, its own answer wins over the document-order first one
        final Operand resolved = this.resolver.resolve(operand, block2);

        assertEquals("two", resolved.get(0));
    }

    @Test
    void widensTheSearchToTheEnclosingEntity()
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        this.context.create().resource("/Submissions/sub", SLING_RESOURCE_TYPE, ENTITY_TYPE);
        this.context.create().resource("/Submissions/sub/block1/a1", Map.of(
            "question", QUESTION_ID, "value", new String[]{ "one" }));
        final Resource block2 = this.context.create().resource("/Submissions/sub/block2");

        // No answer inside block2 itself, so the search widens to the whole submission
        final Operand resolved = this.resolver.resolve(operand, block2);

        assertEquals("one", resolved.get(0));
    }

    @Test
    void resolvesToEmptyWhenThereIsNoAnswer()
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        final Resource submission = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
    }

    @Test
    void stopsSearchingAtTheRootWithoutAnEnclosingEntity()
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        // Not inside any entity, and holding no answers anywhere up to the root
        final Resource orphan = this.context.create().resource("/orphan/deeply/nested");

        assertTrue(this.resolver.resolve(operand, orphan).isEmpty());
    }

    @Test
    void resolvesToEmptyWhenTheQuestionIsNotIdentified()
    {
        final ConditionOperand operand = this.createOperand();
        final Resource submission = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
    }

    @Test
    void resolvesToEmptyWhenThePathDoesNotResolve()
    {
        final ConditionOperand operand = this.createOperand("form/missing");
        final Resource submission = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
    }

    @Test
    void resolvesToEmptyWhenThePathIsNotReferenceable()
    {
        final ConditionOperand operand = this.createOperand("form/q1");
        // The question exists but has no jcr:uuid to match answers against
        this.context.create().resource("/Schemas/schema/1.0/form/q1", Map.of("text", "Question?"));
        final Resource submission = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
    }

    @Test
    void resolvesToEmptyWhenTheOperandIsOutsideAnyEntity()
    {
        final ConditionOperand operand = this.context.create().resource("/orphan/operandA", Map.of(
            SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE,
            "source", "answer",
            "value", new String[]{ "form/q1" })).adaptTo(ConditionOperand.class);
        final Resource submission = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
    }
}
