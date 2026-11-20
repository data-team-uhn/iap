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
package io.uhndata.iap.workflows.internal.handlers;

import java.util.Map;
import java.util.stream.IntStream;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.internal.EngineFixture;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;
import io.uhndata.iap.workflows.testing.RecordingTaskContext;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CreateEntityHandler}: deriving a node name from the title, dodging collisions, and
 * refusing unusable configuration or payload.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CreateEntityHandlerTest
{
    /** Who the executions under test are acting for; the handler itself does not care, the engine records it. */
    private static final String ACTOR = "admin";

    private final SlingContext context = new SlingContext();

    private final CreateEntityHandler handler = new CreateEntityHandler();

    private Resource target;

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.target = EngineFixture.createTarget(this.context);
        EngineFixture.createSystemWorkflow(this.context, "wf/WorkflowsHomepage");
        EngineFixture.createBootstrapGraph(this.context);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(CreateEntityHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void createsTheConfiguredEntityNamedAfterTheTitle() throws WorkflowException, PersistenceException
    {
        final RecordingTaskContext task = context("My cool workflow");

        this.handler.execute(task);

        assertEquals("/Workflows/myCoolWorkflow", task.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
        final Resource created = this.context.resourceResolver().getResource("/Workflows/myCoolWorkflow");
        assertNotNull(created);
        assertEquals("wf:WorkflowDefinition", created.getValueMap().get("jcr:primaryType"));
        assertEquals("My cool workflow", created.getValueMap().get("title"));
    }

    @Test
    void dodgesNameCollisions() throws WorkflowException, PersistenceException
    {
        this.context.create().resource("/Workflows/myCoolWorkflow", TYPE, "wf/WorkflowDefinition");
        this.context.create().resource("/Workflows/myCoolWorkflow2", TYPE, "wf/WorkflowDefinition");
        final RecordingTaskContext task = context("My cool workflow");

        this.handler.execute(task);

        assertEquals("/Workflows/myCoolWorkflow3", task.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
    }

    // A hundred workflows called the same thing is a naming problem, not a reason to refuse the
    // hundred-and-first. The name gets ugly and the entity still gets made.
    @Test
    void createsAnEntityEvenWhenEveryCountedNameVariantIsTaken() throws WorkflowException, PersistenceException
    {
        IntStream.rangeClosed(1, 100).forEach(attempt -> this.context.create().resource(
            "/Workflows/" + (attempt == 1 ? "busy" : "busy" + attempt), TYPE, "wf/WorkflowDefinition"));
        final RecordingTaskContext task = context("Busy");

        this.handler.execute(task);

        assertTrue(((String) task.getVariable(WorkflowResult.CREATED_PATH_VARIABLE))
            .matches("/Workflows/busy[0-9]{9}"));
    }

    @Test
    void refusesActivitiesNotConfiguringAnEntityType()
    {
        // An activity of the graph that carries no entityType configuration
        this.context.create().resource(EngineFixture.VERSION + "/misconfigured", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "misconfigured", "handler", CreateEntityHandler.HANDLER_NAME));
        final WorkflowTaskContext taskContext =
            creating(Map.of("title", "Fine"), adaptActivity(EngineFixture.VERSION + "/misconfigured"));

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(taskContext));
    }

    @Test
    void refusesABlankEntityTypeConfiguration()
    {
        this.context.create().resource(EngineFixture.VERSION + "/blank", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "blank", "handler", CreateEntityHandler.HANDLER_NAME,
            "entityType", " "));
        final WorkflowTaskContext taskContext =
            creating(Map.of("title", "Fine"), adaptActivity(EngineFixture.VERSION + "/blank"));

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(taskContext));
    }

    @Test
    void requiresATitle()
    {
        final InvalidPayloadException rejection = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(null)));
        assertTrue(rejection.getMessage().contains("title is required"));
    }

    @Test
    void refusesABlankTitle()
    {
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(context("   ")));
    }

    @Test
    void refusesATitleWithNothingToNameBy()
    {
        final InvalidPayloadException rejection = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context("!!! ???")));
        assertTrue(rejection.getMessage().contains("letter or digit"));
    }

    @Test
    void variablesCanBeReadBackThroughTheContext() throws WorkflowException, PersistenceException
    {
        // The get side of the context contract: what one task leaves behind, a later task can read
        final WorkflowTaskContext taskContext = context("Round trip");

        this.handler.execute(taskContext);

        assertEquals("/Workflows/roundTrip", taskContext.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
        assertNull(taskContext.getVariable("neverSet"));
    }

    @Test
    void ignoresLeadingAndTrailingPunctuationInTitles() throws WorkflowException, PersistenceException
    {
        final RecordingTaskContext task = context("  (Urgent) reviews!  ");

        this.handler.execute(task);

        assertEquals("/Workflows/urgentReviews", task.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
    }

    @Test
    void handlesSingleWordAndUnicodeTitles() throws WorkflowException, PersistenceException
    {
        final RecordingTaskContext first = context("REVIEWS");
        this.handler.execute(first);
        assertEquals("/Workflows/reviews", first.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));

        final RecordingTaskContext second = context("Évaluation des congés");
        this.handler.execute(second);
        assertEquals("/Workflows/évaluationDesCongés", second.getVariable(WorkflowResult.CREATED_PATH_VARIABLE));
    }

    /**
     * Builds a task context for the bootstrap graph's own create activity, with the given title in the payload.
     *
     * @param title the payload title, or {@code null} to send no title at all
     * @return the assembled context
     */
    private RecordingTaskContext context(final String title)
    {
        final Map<String, Object> payload = title == null ? Map.of() : Map.of("title", title);
        return creating(payload, adaptActivity(EngineFixture.VERSION + "/create"));
    }

    private RecordingTaskContext creating(final Map<String, Object> payload, final Activity activity)
    {
        return RecordingTaskContext.aimedAt(this.target).forEvent("create", payload).performing(activity)
            .actingFor(ACTOR).build();
    }

    private Activity adaptActivity(final String path)
    {
        return this.context.resourceResolver().getResource(path).adaptTo(Activity.class);
    }
}
