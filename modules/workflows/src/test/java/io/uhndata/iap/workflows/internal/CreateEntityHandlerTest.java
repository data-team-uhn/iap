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

import java.util.HashMap;
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
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowFixture;

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
        EngineFixture.createSystemWorkflow(this.context, true, true, "wf/WorkflowsHomepage");
        EngineFixture.createBootstrapGraph(this.context);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(CreateEntityHandler.NAME, this.handler.getName());
    }

    @Test
    void createsTheConfiguredEntityNamedAfterTheTitle() throws WorkflowException, PersistenceException
    {
        final Map<String, Object> variables = new HashMap<>();

        this.handler.execute(context("My cool workflow", variables));

        assertEquals("/Workflows/myCoolWorkflow", variables.get(WorkflowResult.CREATED_PATH));
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
        final Map<String, Object> variables = new HashMap<>();

        this.handler.execute(context("My cool workflow", variables));

        assertEquals("/Workflows/myCoolWorkflow3", variables.get(WorkflowResult.CREATED_PATH));
    }

    @Test
    void givesUpWhenEveryNameVariantIsTaken()
    {
        IntStream.rangeClosed(1, 100).forEach(attempt -> this.context.create().resource(
            "/Workflows/" + (attempt == 1 ? "busy" : "busy" + attempt), TYPE, "wf/WorkflowDefinition"));

        final InvalidPayloadException rejection = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context("Busy", new HashMap<>())));
        assertTrue(rejection.getMessage().contains("pick a different title"));
    }

    @Test
    void refusesActivitiesNotConfiguringAnEntityType()
    {
        // An activity of the graph that carries no entityType configuration
        this.context.create().resource(EngineFixture.VERSION + "/misconfigured", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "misconfigured", "handler", CreateEntityHandler.NAME));
        final WorkflowTaskContextImpl taskContext = new WorkflowTaskContextImpl(this.target,
            new WorkflowEvent("create", Map.of("title", "Fine")),
            adaptActivity(EngineFixture.VERSION + "/misconfigured"), new HashMap<>(), ACTOR);

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(taskContext));
    }

    @Test
    void refusesABlankEntityTypeConfiguration()
    {
        this.context.create().resource(EngineFixture.VERSION + "/blank", Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "blank", "handler", CreateEntityHandler.NAME,
            "entityType", " "));
        final WorkflowTaskContextImpl taskContext = new WorkflowTaskContextImpl(this.target,
            new WorkflowEvent("create", Map.of("title", "Fine")),
            adaptActivity(EngineFixture.VERSION + "/blank"), new HashMap<>(), ACTOR);

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(taskContext));
    }

    @Test
    void requiresATitle()
    {
        final InvalidPayloadException rejection = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(null, new HashMap<>())));
        assertTrue(rejection.getMessage().contains("title is required"));
    }

    @Test
    void refusesABlankTitle()
    {
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(context("   ", new HashMap<>())));
    }

    @Test
    void refusesATitleWithNothingToNameBy()
    {
        final InvalidPayloadException rejection = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context("!!! ???", new HashMap<>())));
        assertTrue(rejection.getMessage().contains("letter or digit"));
    }

    @Test
    void variablesCanBeReadBackThroughTheContext() throws WorkflowException, PersistenceException
    {
        // The get side of the context contract: what one task leaves behind, a later task can read
        final WorkflowTaskContextImpl taskContext = context("Round trip", new HashMap<>());

        this.handler.execute(taskContext);

        assertEquals("/Workflows/roundTrip", taskContext.getVariable(WorkflowResult.CREATED_PATH));
        assertNull(taskContext.getVariable("neverSet"));
    }

    @Test
    void ignoresLeadingAndTrailingPunctuationInTitles() throws WorkflowException, PersistenceException
    {
        final Map<String, Object> variables = new HashMap<>();

        this.handler.execute(context("  (Urgent) reviews!  ", variables));

        assertEquals("/Workflows/urgentReviews", variables.get(WorkflowResult.CREATED_PATH));
    }

    @Test
    void handlesSingleWordAndUnicodeTitles() throws WorkflowException, PersistenceException
    {
        final Map<String, Object> first = new HashMap<>();
        this.handler.execute(context("REVIEWS", first));
        assertEquals("/Workflows/reviews", first.get(WorkflowResult.CREATED_PATH));

        final Map<String, Object> second = new HashMap<>();
        this.handler.execute(context("Évaluation des congés", second));
        assertEquals("/Workflows/évaluationDesCongés", second.get(WorkflowResult.CREATED_PATH));
    }

    /**
     * Builds a task context for the bootstrap graph's own create activity, with the given title in the payload.
     *
     * @param title the payload title, or {@code null} to send no title at all
     * @param variables where the handler reports its results
     * @return the assembled context
     */
    private WorkflowTaskContextImpl context(final String title, final Map<String, Object> variables)
    {
        final Map<String, Object> payload = title == null ? Map.of() : Map.of("title", title);
        return new WorkflowTaskContextImpl(this.target, new WorkflowEvent("create", payload),
            adaptActivity(EngineFixture.VERSION + "/create"), variables, ACTOR);
    }

    private Activity adaptActivity(final String path)
    {
        return this.context.resourceResolver().getResource(path).adaptTo(Activity.class);
    }
}
