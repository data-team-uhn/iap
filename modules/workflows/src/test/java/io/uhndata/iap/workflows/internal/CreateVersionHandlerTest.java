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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowConflictException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CreateVersionHandler}: opening a draft version of an existing workflow, with the diagram
 * that arrived with the request, and refusing a label the workflow already carries.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CreateVersionHandlerTest
{
    private final SlingContext context = new SlingContext();

    private final CreateVersionHandler handler = new CreateVersionHandler();

    private Activity activity;

    @BeforeEach
    void setUp()
    {
        AuthoringFixture.setUp(this.context);
        this.activity = AuthoringFixture.activity(this.context, "create",
            Map.of("handler", CreateVersionHandler.NAME));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(CreateVersionHandler.NAME, this.handler.getName());
    }

    @Test
    void createsADraftNamedAfterTheLabel() throws WorkflowException, PersistenceException
    {
        final Map<String, Object> variables = new HashMap<>();

        this.handler.execute(this.request(Map.of("version", "1.0"), variables));

        assertEquals(AuthoringFixture.path("1-0"), variables.get(WorkflowResult.CREATED_PATH));
        final Resource created = this.context.resourceResolver().getResource(AuthoringFixture.path("1-0"));
        assertNotNull(created);
        assertEquals("wf:WorkflowVersion", created.getValueMap().get("jcr:primaryType"));
        assertEquals("1.0", created.getValueMap().get("version"));
        assertEquals("DRAFT", created.getValueMap().get("state"));
        // A version authored here is owned by its diagram: nothing else could derive its flow nodes
        assertEquals(Boolean.TRUE, created.getValueMap().get("bpmnAuthoritative", Boolean.class));
        assertNull(created.getValueMap().get("description"));
        assertNull(created.getChild("bpmn.xml"));
    }

    @Test
    void storesTheDiagramThatArrivedWithTheRequest() throws WorkflowException, PersistenceException, IOException
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("version", "1.0");
        payload.put("bpmn.xml", AuthoringFixture.upload(AuthoringFixture.BPMN, "application/xml"));

        this.handler.execute(this.request(payload, new HashMap<>()));

        final Resource created = this.context.resourceResolver().getResource(AuthoringFixture.path("1-0"));
        assertNotNull(created);
        assertEquals(AuthoringFixture.BPMN, AuthoringFixture.read(created.getChild("bpmn.xml")));
        assertEquals("application/xml",
            created.getChild("bpmn.xml/jcr:content").getValueMap().get("jcr:mimeType"));
    }

    @Test
    void recordsTheDescriptionWhenOneIsGiven() throws WorkflowException, PersistenceException
    {
        this.handler.execute(this.request(Map.of("version", "1.0", "description", "  The first cut  "),
            new HashMap<>()));

        final Resource created = this.context.resourceResolver().getResource(AuthoringFixture.path("1-0"));
        assertNotNull(created);
        assertEquals("The first cut", created.getValueMap().get("description"));
    }

    @Test
    void ignoresABlankDescription() throws WorkflowException, PersistenceException
    {
        this.handler.execute(this.request(Map.of("version", "1.0", "description", "   "), new HashMap<>()));

        final Resource created = this.context.resourceResolver().getResource(AuthoringFixture.path("1-0"));
        assertNotNull(created);
        assertNull(created.getValueMap().get("description"));
    }

    @Test
    void findsAFreeNodeNameWhenTheDerivedOneIsTaken() throws WorkflowException, PersistenceException
    {
        // Two labels that reduce to the same node name are told apart by an appended counter
        AuthoringFixture.createVersion(this.context, "1-0", "1/0", WorkflowVersion.State.DRAFT, Map.of());

        this.handler.execute(this.request(Map.of("version", "1.0"), new HashMap<>()));

        assertNotNull(this.context.resourceResolver().getResource(AuthoringFixture.path("1-0-2")));
    }

    @Test
    void namesAVersionWhoseLabelReducesToNothing() throws WorkflowException, PersistenceException
    {
        final Map<String, Object> variables = new HashMap<>();

        this.handler.execute(this.request(Map.of("version", "!!!"), variables));

        assertEquals(AuthoringFixture.path("version"), variables.get(WorkflowResult.CREATED_PATH));
    }

    @Test
    void refusesALabelTheWorkflowAlreadyCarries()
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", WorkflowVersion.State.ACTIVE, Map.of());

        final WorkflowConflictException refusal = assertThrows(WorkflowConflictException.class,
            () -> this.handler.execute(this.request(Map.of("version", "1.0"), new HashMap<>())));
        assertTrue(refusal.getMessage().contains("already has a version 1.0"));
    }

    @Test
    void requiresALabel()
    {
        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(this.request(Map.of(), new HashMap<>())));
        assertTrue(refusal.getMessage().contains("version is required"));
    }

    @Test
    void refusesABlankLabel()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(this.request(Map.of("version", "   "), new HashMap<>())));
    }

    @Test
    void refusesALabelThatIsNotText()
    {
        // A channel other than a form could carry anything at all under that name
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(this.request(Map.of("version", new String[] { "1.0" }), new HashMap<>())));
    }

    @Test
    void reportsAnUploadThatCannotBeRead()
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("version", "1.0");
        payload.put("bpmn.xml", AuthoringFixture.brokenUpload());

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(this.request(payload, new HashMap<>())));
        assertTrue(failure.getMessage().contains("The upload broke"));
    }

    /**
     * A task context aimed at the fixture's definition.
     *
     * @param payload what the event carries
     * @param variables where the handler reports its results
     * @return the assembled context
     */
    private WorkflowTaskContextImpl request(final Map<String, Object> payload,
        final Map<String, Object> variables)
    {
        return AuthoringFixture.context(this.context.resourceResolver().getResource(AuthoringFixture.DEFINITION),
            "createVersion", payload, this.activity, variables);
    }
}
