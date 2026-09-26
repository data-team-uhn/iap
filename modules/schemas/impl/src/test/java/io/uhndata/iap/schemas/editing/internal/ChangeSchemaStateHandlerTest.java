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
package io.uhndata.iap.schemas.editing.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.schemas.models.LifecycleState;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ChangeSchemaStateHandler}, including the {@link PublishCheck} guarding the first
 * activation of a draft.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ChangeSchemaStateHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final ChangeSchemaStateHandler handler = new ChangeSchemaStateHandler();

    private SchemaFixture fixture;

    private Resource schema;

    @BeforeEach
    void setUp() throws PersistenceException
    {
        this.fixture = new SchemaFixture(this.context);
        this.schema = this.fixture.schema("study");
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(ChangeSchemaStateHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void publishesADraftThatPassesTheChecks() throws WorkflowException, PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft", "sensitive");
        this.fixture.create(draft.getPath(), "form", "sch:FormRequirement", Map.of("label", "Form"));
        this.fixture.create(draft.getPath() + "/form", "age", "sch:Question", Map.of("text", "Age",
            "dataType", "long", "minAnswers", 1L, "maxAnswers", 1L, "minValue", 0.0d, "maxValue", 120.0d,
            "pattern", "[0-9]+"));

        this.handler.execute(state(draft, "active"));

        assertEquals(LifecycleState.ACTIVE, version(draft).getState());
        // Only the lifecycle tag is swapped; other tags stay
        assertTrue(List.of(draft.getValueMap().get("tags", String[].class)).contains("sensitive"));
    }

    @Test
    void togglesBetweenActiveAndRetired() throws WorkflowException, PersistenceException
    {
        final Resource version = this.fixture.version(this.schema, "v1", "active");

        this.handler.execute(state(version, "retired"));
        assertEquals(LifecycleState.RETIRED, version(version).getState());

        this.handler.execute(state(version, "active"));
        assertEquals(LifecycleState.ACTIVE, version(version).getState());
    }

    @Test
    void refusesTransitionsTheLifecycleDoesNotHave() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");
        final Resource active = this.fixture.version(this.schema, "v2", "active");
        final Resource retired = this.fixture.version(this.schema, "v3", "retired");

        assertThrows(NoApplicableWorkflowException.class, () -> this.handler.execute(state(draft, "retired")));
        assertThrows(NoApplicableWorkflowException.class, () -> this.handler.execute(state(active, "active")));
        assertThrows(NoApplicableWorkflowException.class, () -> this.handler.execute(state(retired, "retired")));
    }

    @Test
    void retiresAndReopensASchema() throws WorkflowException, PersistenceException
    {
        this.handler.execute(state(this.schema, "retired"));
        assertEquals(LifecycleState.RETIRED, this.schema.adaptTo(Schema.class).getState());
        assertThrows(NoApplicableWorkflowException.class, () -> this.handler.execute(state(this.schema, "retired")));

        this.handler.execute(state(this.schema, "active"));
        assertEquals(LifecycleState.ACTIVE, this.schema.adaptTo(Schema.class).getState());
        assertEquals(0, this.schema.getValueMap().get("tags", new String[0]).length);
    }

    @Test
    void needsAKnownStateAndAKnownTarget()
    {
        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(state(this.schema, "draft")));
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(new TaskContext(this.schema, Map.of(), Map.of())));
        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(state(this.fixture.get("/Schemas"), "active")));
    }

    @Test
    void reportsEverythingThatStopsADraftFromBeingPublished() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");
        final ResourceResolver resolver = this.context.resourceResolver();
        final Resource form = resolver.create(draft, "form", Map.of("jcr:primaryType", "sch:FormRequirement",
            "label", "Form"));
        final Resource counts = resolver.create(form, "counts", Map.of("jcr:primaryType", "sch:Question",
            "text", "Counts", "minAnswers", 3L, "maxAnswers", 2L, "minValue", 5.0d, "maxValue", 1.0d,
            "pattern", "(unclosed"));
        resolver.create(counts, "a", Map.of("jcr:primaryType", "sch:AnswerOption", "value", "same"));
        resolver.create(counts, "b", Map.of("jcr:primaryType", "sch:AnswerOption", "value", "same"));
        resolver.create(counts, "c", Map.of("jcr:primaryType", "sch:AnswerOption", "value", " "));
        final Resource section = resolver.create(form, "section", Map.of("jcr:primaryType", "sch:Section",
            "title", "Details"));
        condition(resolver, section, "sometimes", Map.of("source", "answer", "value", new String[] { "form/gone" },
            "aggregate", "median"));
        final Resource outside = resolver.create(this.fixture.version(this.schema, "v0", "retired"), "q",
            Map.of("jcr:primaryType", "sch:Question", "text", "Elsewhere"));
        resolver.commit();
        final String elsewhere = outside.getValueMap().get("jcr:uuid", String.class);
        final Resource requirement = resolver.create(draft, "docs", Map.of("jcr:primaryType",
            "sch:DocumentRequirement", "label", "Documents"));
        condition(resolver, requirement, "equals", Map.of("source", "answer", "value", new String[] { elsewhere }));
        final Resource approval = resolver.create(draft, "approval", Map.of("jcr:primaryType",
            "sch:ApprovalRequirement", "label", "Approval"));
        condition(resolver, approval, "equals",
            Map.of("source", "answer", "value", new String[] { "00000000-0000-0000-0000-000000000000" }));
        resolver.commit();

        final NoApplicableWorkflowException refusal =
            assertThrows(NoApplicableWorkflowException.class, () -> this.handler.execute(state(draft, "active")));

        final String message = refusal.getMessage();
        assertTrue(message.contains("\"Counts\" asks for at least 3 answers but allows at most 2"), message);
        assertTrue(message.contains("\"Counts\" has a smallest accepted value above its largest"), message);
        assertTrue(message.contains("\"Counts\" has a pattern that is not a valid regular expression"), message);
        assertTrue(message.contains("\"Counts\" offers the value \"same\" more than once"), message);
        assertTrue(message.contains("\"Counts\" has an option without a value"), message);
        assertTrue(message.contains("\"Details\" has a condition comparing with \"sometimes\""), message);
        assertTrue(message.contains("\"Details\" has a condition aggregating with \"median\""), message);
        assertTrue(message.contains("\"Details\" has a condition on a question that is not in this version"),
            message);
        assertTrue(message.contains("\"Documents\" has a condition on a question"), message);
        assertTrue(message.contains("\"Approval\" has a condition on a question"), message);
        assertEquals(LifecycleState.DRAFT, version(draft).getState());
    }

    @Test
    void acceptsConditionsOnQuestionsOfTheSameVersion() throws WorkflowException, PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");
        final ResourceResolver resolver = this.context.resourceResolver();
        final Resource form = resolver.create(draft, "form", Map.of("jcr:primaryType", "sch:FormRequirement",
            "label", "Form"));
        final Resource question = resolver.create(form, "consent", Map.of("jcr:primaryType", "sch:Question",
            "text", "Consent?", "dataType", "boolean", "maxAnswers", 0L, "minAnswers", 4L));
        resolver.commit();
        final String uuid = question.getValueMap().get("jcr:uuid", String.class);
        final Resource byPath = resolver.create(form, "byPath", Map.of("jcr:primaryType", "sch:Section",
            "title", "By path"));
        condition(resolver, byPath, "equals", Map.of("source", "answer", "value", new String[] { "form/consent" },
            "aggregate", "count"));
        final Resource byUuid = resolver.create(form, "byUuid", Map.of("jcr:primaryType", "sch:Section",
            "title", "By identifier"));
        condition(resolver, byUuid, "is not empty", Map.of("source", "answer", "value", new String[] { uuid }));
        final Resource onTags = resolver.create(form, "onTags", Map.of("jcr:primaryType", "sch:Section",
            "title", "Tags"));
        condition(resolver, onTags, "includes", Map.of("source", "tags"));
        resolver.commit();

        this.handler.execute(state(draft, "active"));

        assertEquals(LifecycleState.ACTIVE, version(draft).getState());
    }

    @Test
    void namesAnUntitledItemByItsPath() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");
        final ResourceResolver resolver = this.context.resourceResolver();
        final Resource form = resolver.create(draft, "form", Map.of("jcr:primaryType", "sch:FormRequirement",
            "label", "Form"));
        final Resource section = resolver.create(form, "nameless", Map.of("jcr:primaryType", "sch:Section",
            "title", " "));
        condition(resolver, section, "equals", Map.of("source", "answer", "value", new String[0]));
        resolver.commit();

        final NoApplicableWorkflowException refusal =
            assertThrows(NoApplicableWorkflowException.class, () -> this.handler.execute(state(draft, "active")));

        assertTrue(refusal.getMessage().contains("form/nameless has a condition on a question"),
            refusal.getMessage());
        assertFalse(refusal.getMessage().contains("comparing"));
    }

    private static void condition(final ResourceResolver resolver, final Resource on, final String comparator,
        final Map<String, Object> operandA) throws PersistenceException
    {
        final Resource condition = resolver.create(on, "cond:condition",
            Map.of("jcr:primaryType", "cond:SingleCondition", "comparator", comparator));
        final Map<String, Object> operand = new HashMap<>(operandA);
        operand.put("jcr:primaryType", "cond:ConditionOperand");
        resolver.create(condition, "operandA", operand);
    }

    private SchemaVersion version(final Resource resource)
    {
        return this.fixture.get(resource.getPath()).adaptTo(SchemaVersion.class);
    }

    private TaskContext state(final Resource target, final String state)
    {
        return new TaskContext(target, Map.of(), Map.of(ChangeSchemaStateHandler.STATE_PARAMETER, state));
    }
}
