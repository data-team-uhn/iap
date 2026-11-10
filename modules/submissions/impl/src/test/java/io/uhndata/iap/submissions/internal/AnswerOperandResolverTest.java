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
package io.uhndata.iap.submissions.internal;

import java.util.Map;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

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
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Submission;

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

    private static final String ENTITY_TYPE = "data/Entity";

    private static final String SUBMISSION_TYPE = "sub/Submission";

    private static final String INSTANCE_TYPE = "wf/WorkflowInstance";

    private static final String QUESTION_ID = "11111111-2222-3333-4444-555555555555";

    private static final String VERSION_ID = "66666666-7777-8888-9999-000000000000";

    private static final String VERSION_PATH = "/Schemas/schema/1.0";

    private final SlingContext context = new SlingContext();

    private final AnswerOperandResolver resolver = new AnswerOperandResolver();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionOperand.class, Submission.class,
            SchemaVersion.class);
        this.context.create().resource(VERSION_PATH, SLING_RESOURCE_TYPE, SchemaVersion.RESOURCE_TYPE);
    }

    /** Creates an operand identifying the target question by the given reference, in a condition of the schema. */
    private ConditionOperand createOperand(final String... reference)
    {
        return this.createOperandAt(VERSION_PATH + "/req/cond:condition/operandA", reference);
    }

    private ConditionOperand createOperandAt(final String path, final String... reference)
    {
        return this.context.create().resource(path, Map.of(
            SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE,
            "source", "answer",
            "value", reference)).adaptTo(ConditionOperand.class);
    }

    /** Creates a submission answering the schema version. */
    private Content createSubmission(final String path)
    {
        return this.context.create().resource(path, SLING_RESOURCE_TYPE, SUBMISSION_TYPE, "schemaVersion", VERSION_ID)
            .adaptTo(Content.class);
    }

    /** Lets submissions reach the schema version they answer through the session. */
    private void makeTheVersionReachable()
        throws RepositoryException
    {
        final Node versionNode = Mockito.mock(Node.class);
        Mockito.when(versionNode.getPath()).thenReturn(VERSION_PATH);
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(VERSION_ID)).thenReturn(versionNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
    }

    private void createAnswer(final String path, final String question, final String value)
    {
        this.context.create().resource(path, Map.of("question", question, "value", new String[] { value }));
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
        final Content submission = this.createSubmission("/Submissions/sub");
        this.createAnswer("/Submissions/sub/a1", QUESTION_ID, "yes");

        final Operand resolved = this.resolver.resolve(operand, submission);

        assertEquals(1, resolved.size());
        assertEquals("yes", resolved.get(0));
        // Without a JCR session the question node cannot be loaded, so its type stays unknown
        assertNull(resolved.getType());
    }

    @Test
    void resolvesAnswersByQuestionPath()
        throws RepositoryException
    {
        this.makeTheVersionReachable();
        final ConditionOperand operand = this.createOperand("form/q1");
        this.context.create().resource("/Schemas/schema/1.0/form/q1", Map.of(
            "jcr:uuid", QUESTION_ID, "dataType", "long"));
        final Content submission = this.createSubmission("/Submissions/sub");
        this.createAnswer("/Submissions/sub/a1", QUESTION_ID, "42");

        final Operand resolved = this.resolver.resolve(operand, submission);

        assertEquals(1, resolved.size());
        assertEquals("42", resolved.get(0));
        // The question's own declared data type steers the comparison type unification
        assertEquals(OperandType.LONG, resolved.getType());
    }

    @Test
    void resolvesAQuestionPathInAWorkflowsGuardAgainstTheSubmissionsSchemaVersion()
        throws RepositoryException
    {
        this.makeTheVersionReachable();
        this.context.create().resource("/WorkflowDefinitions/timeOff/v1", SLING_RESOURCE_TYPE, ENTITY_TYPE);
        final ConditionOperand operand =
            this.createOperandAt("/WorkflowDefinitions/timeOff/v1/route/toLong/cond:condition/operandA", "form/q1");
        this.context.create().resource("/Schemas/schema/1.0/form/q1", Map.of(
            "jcr:uuid", QUESTION_ID, "dataType", "long"));
        this.createSubmission("/Submissions/sub");
        this.createAnswer("/Submissions/sub/a1", QUESTION_ID, "42");
        final Content instance = this.context.create().resource("/Submissions/sub/wf:instances/timeOff",
            SLING_RESOURCE_TYPE, INSTANCE_TYPE).adaptTo(Content.class);

        final Operand resolved = this.resolver.resolve(operand, instance);

        assertEquals("42", resolved.get(0));
        assertEquals(OperandType.LONG, resolved.getType());
    }

    @Test
    void resolvesAQuestionPathInASystemWorkflowsStartGuardAgainstTheTargetsSchemaVersion()
        throws RepositoryException
    {
        this.makeTheVersionReachable();
        this.context.create().resource("/SystemWorkflows/escalate/v1", SLING_RESOURCE_TYPE, ENTITY_TYPE);
        final ConditionOperand operand =
            this.createOperandAt("/SystemWorkflows/escalate/v1/requested/cond:condition/operandA", "form/q1");
        this.context.create().resource("/Schemas/schema/1.0/form/q1", Map.of("jcr:uuid", QUESTION_ID));
        final Content target = this.createSubmission("/Submissions/sub");
        this.createAnswer("/Submissions/sub/a1", QUESTION_ID, "yes");

        assertEquals("yes", this.resolver.resolve(operand, target).get(0));
    }

    @Test
    void takesAnIdentifierOnlyInTheLowerCaseTheRepositoryWrites()
    {
        // Spelt otherwise, it is a path, which leads nowhere
        final String upper = "AAAAAAAA-2222-3333-4444-555555555555";
        final ConditionOperand operand = this.createOperand(upper);
        final Content submission = this.createSubmission("/Submissions/sub");
        this.createAnswer("/Submissions/sub/a1", upper, "yes");

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
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
        final Content submission = this.createSubmission("/Submissions/sub");
        this.createAnswer("/Submissions/sub/a1", QUESTION_ID, "42");

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
        final Content submission = this.createSubmission("/Submissions/sub");
        this.createAnswer("/Submissions/sub/a1", QUESTION_ID, "yes");

        // The answer is still matched by the identifier itself; only the type stays unknown
        final Operand resolved = this.resolver.resolve(operand, submission);

        assertEquals("yes", resolved.get(0));
        assertNull(resolved.getType());
    }

    @Test
    void prefersTheAnswerClosestToTheContext()
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        this.createSubmission("/Submissions/sub");
        this.createAnswer("/Submissions/sub/block1/a1", QUESTION_ID, "one");
        final Content block2 = this.context.create().resource("/Submissions/sub/block2")
            .adaptTo(Content.class);
        this.createAnswer("/Submissions/sub/block2/a2", QUESTION_ID, "two");

        // Evaluated inside the second repeated block, its own answer wins over the document-order first one
        final Operand resolved = this.resolver.resolve(operand, block2);

        assertEquals("two", resolved.get(0));
    }

    @Test
    void widensTheSearchToTheEnclosingSubmission()
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        this.createSubmission("/Submissions/sub");
        this.createAnswer("/Submissions/sub/block1/a1", QUESTION_ID, "one");
        final Content block2 = this.context.create().resource("/Submissions/sub/block2")
            .adaptTo(Content.class);

        // No answer inside block2 itself, so the search widens to the whole submission
        final Operand resolved = this.resolver.resolve(operand, block2);

        assertEquals("one", resolved.get(0));
    }

    @Test
    void resolvesToEmptyWhenThereIsNoAnswer()
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        final Content submission = this.createSubmission("/Submissions/sub");

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
    }

    @Test
    void readsTheSubmissionsAnswerFromAWorkflowInstance()
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        this.createSubmission("/Submissions/sub");
        this.createAnswer("/Submissions/sub/a1", QUESTION_ID, "40");
        final Content instance = this.context.create().resource("/Submissions/sub/wf:instances/timeOff",
            SLING_RESOURCE_TYPE, INSTANCE_TYPE).adaptTo(Content.class);

        assertEquals("40", this.resolver.resolve(operand, instance).get(0));
    }

    @Test
    void doesNotReadAnotherSubmissionsAnswer()
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        final Content mine = this.createSubmission("/Submissions/mine");
        this.createSubmission("/Submissions/theirs");
        this.createAnswer("/Submissions/theirs/a1", QUESTION_ID, "theirs");

        assertTrue(this.resolver.resolve(operand, mine).isEmpty());
    }

    @Test
    void findsNoAnswerOutsideASubmission()
    {
        final ConditionOperand operand = this.createOperand(QUESTION_ID);
        this.createSubmission("/Submissions/theirs");
        this.createAnswer("/Submissions/theirs/a1", QUESTION_ID, "theirs");
        final Content orphan = this.context.create().resource("/orphan/deeply/nested").adaptTo(Content.class);

        assertTrue(this.resolver.resolve(operand, orphan).isEmpty());
    }

    @Test
    void resolvesToEmptyWhenTheQuestionIsNotIdentified()
    {
        final ConditionOperand operand = this.createOperand();
        final Content submission = this.createSubmission("/Submissions/sub");

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
    }

    @Test
    void resolvesToEmptyWhenThePathDoesNotResolve()
        throws RepositoryException
    {
        this.makeTheVersionReachable();
        final ConditionOperand operand = this.createOperand("form/missing");
        final Content submission = this.createSubmission("/Submissions/sub");

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
    }

    @Test
    void resolvesToEmptyWhenThePathIsNotReferenceable()
        throws RepositoryException
    {
        this.makeTheVersionReachable();
        final ConditionOperand operand = this.createOperand("form/q1");
        // The question exists but has no jcr:uuid to match answers against
        this.context.create().resource("/Schemas/schema/1.0/form/q1", Map.of("text", "Question?"));
        final Content submission = this.createSubmission("/Submissions/sub");

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
    }

    @Test
    void resolvesToEmptyWhenTheSubmissionCannotReachItsSchemaVersion()
    {
        final ConditionOperand operand = this.createOperand("form/q1");
        this.context.create().resource("/Schemas/schema/1.0/form/q1", Map.of("jcr:uuid", QUESTION_ID));
        final Content submission = this.createSubmission("/Submissions/sub");
        this.createAnswer("/Submissions/sub/a1", QUESTION_ID, "yes");

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
    }
}
