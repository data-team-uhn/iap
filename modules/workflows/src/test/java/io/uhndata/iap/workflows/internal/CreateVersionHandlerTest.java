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

import javax.jcr.RepositoryException;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;

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
    void createsADraftNamedAfterItsPosition() throws WorkflowException, PersistenceException
    {
        final Map<String, Object> variables = new HashMap<>();

        this.handler.execute(this.request(Map.of("version", "1.0"), variables));

        assertEquals(AuthoringFixture.path("v1"), variables.get(WorkflowResult.CREATED_PATH_VARIABLE));
        final Resource created = this.context.resourceResolver().getResource(AuthoringFixture.path("v1"));
        assertNotNull(created);
        assertEquals("wf:WorkflowVersion", created.getValueMap().get("jcr:primaryType"));
        assertEquals("1.0", created.getValueMap().get("version"));
        // Marking it a draft is the next step's, as every lifecycle tag is the workflow's to place
        assertNull(created.getValueMap().get("tags", String[].class));
        // A version authored here is owned by its diagram: nothing else could derive its flow nodes
        assertEquals(Boolean.TRUE, created.getValueMap().get("bpmnAuthoritative", Boolean.class));
        assertNull(created.getValueMap().get("description"));
        assertNull(created.getChild("bpmn.xml"));
    }

    @Test
    void createsTheVersionInWhatAnEarlierStepCreated() throws WorkflowException, PersistenceException
    {
        // createWorkflow runs createEntity at the homepage first: the version belongs to the workflow it made,
        // not to the homepage the event was sent to
        this.context.create().resource("/Workflows/fresh", "jcr:primaryType", "wf:WorkflowDefinition");
        final Map<String, Object> variables = new HashMap<>();
        variables.put(WorkflowResult.CREATED_PATH_VARIABLE, "/Workflows/fresh");

        this.handler.execute(AuthoringFixture.context(this.context.resourceResolver().getResource("/Workflows"),
            "create", Map.of("version", "1.0"), this.activity, variables));

        assertEquals("/Workflows/fresh/v1", variables.get(WorkflowResult.CREATED_PATH_VARIABLE));
        assertNotNull(this.context.resourceResolver().getResource("/Workflows/fresh/v1"));
    }

    @Test
    void storesTheDiagramThatArrivedWithTheRequest() throws WorkflowException, PersistenceException, IOException
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("version", "1.0");
        payload.put("bpmn.xml", AuthoringFixture.upload(AuthoringFixture.BPMN, "application/xml"));

        this.handler.execute(this.request(payload, new HashMap<>()));

        final Resource created = this.context.resourceResolver().getResource(AuthoringFixture.path("v1"));
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

        final Resource created = this.context.resourceResolver().getResource(AuthoringFixture.path("v1"));
        assertNotNull(created);
        assertEquals("The first cut", created.getValueMap().get("description"));
    }

    @Test
    void ignoresABlankDescription() throws WorkflowException, PersistenceException
    {
        this.handler.execute(this.request(Map.of("version", "1.0", "description", "   "), new HashMap<>()));

        final Resource created = this.context.resourceResolver().getResource(AuthoringFixture.path("v1"));
        assertNotNull(created);
        assertNull(created.getValueMap().get("description"));
    }

    @Test
    void skipsANodeNameThatIsAlreadyTaken() throws WorkflowException, PersistenceException
    {
        // One version so far, but already stored under the name the second would get
        AuthoringFixture.createVersion(this.context, "v2", "1.0", "draft", Map.of());
        final Map<String, Object> variables = new HashMap<>();

        this.handler.execute(this.request(Map.of("version", "2.0"), variables));

        assertEquals(AuthoringFixture.path("v3"), variables.get(WorkflowResult.CREATED_PATH_VARIABLE));
    }

    @Test
    void keepsALabelThatCouldNotBeANodeName() throws WorkflowException, PersistenceException
    {
        // The name does not come from the label, so a label may say anything
        this.handler.execute(this.request(Map.of("version", "2.0 (pilot)"), new HashMap<>()));

        final Resource created = this.context.resourceResolver().getResource(AuthoringFixture.path("v1"));
        assertNotNull(created);
        assertEquals("2.0 (pilot)", created.getValueMap().get("version"));
    }

    @Test
    void refusesALabelTheWorkflowAlreadyCarries()
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", "active", Map.of());

        final InvalidStateException refusal = assertThrows(InvalidStateException.class,
            () -> this.handler.execute(this.request(Map.of("version", "1.0"), new HashMap<>())));
        assertTrue(refusal.getMessage().contains("already has a version 1.0"));
    }

    @Test
    void labelsAFirstVersionOnePointZeroWhenNoLabelIsGiven() throws WorkflowException, PersistenceException
    {
        this.handler.execute(this.request(Map.of(), new HashMap<>()));

        assertEquals("1.0", this.context.resourceResolver().getResource(AuthoringFixture.path("v1"))
            .getValueMap().get("version"));
    }

    @Test
    void labelsALaterVersionWithTheWholeNumberAfterTheHighest() throws WorkflowException, PersistenceException
    {
        // A label that is not a number, or not a finite one, takes no part in which number comes next
        AuthoringFixture.createVersion(this.context, "v1", "1.0", "retired", Map.of());
        AuthoringFixture.createVersion(this.context, "v2", "2.5", "active", Map.of());
        AuthoringFixture.createVersion(this.context, "v3", "beta", "draft", Map.of());
        AuthoringFixture.createVersion(this.context, "v4", "Infinity", "draft", Map.of());

        this.handler.execute(this.request(Map.of(), new HashMap<>()));

        assertEquals("3.0", this.context.resourceResolver().getResource(AuthoringFixture.path("v5"))
            .getValueMap().get("version"));
    }

    @Test
    void numbersAfterTheVersionCountWhenNoLabelIsANumber() throws WorkflowException, PersistenceException
    {
        AuthoringFixture.createVersion(this.context, "v1", "alpha", "draft", Map.of());

        this.handler.execute(this.request(Map.of(), new HashMap<>()));

        assertEquals("2.0", this.context.resourceResolver().getResource(AuthoringFixture.path("v2"))
            .getValueMap().get("version"));
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

    @Test
    void checksOutTheWorkflowBeforeAddingAVersion() throws WorkflowException, PersistenceException, RepositoryException
    {
        // A checked-in node takes no children, and the Sling POST servlet checks in whatever it creates
        final VersionManager versions = AuthoringFixture.checkedIn(this.context, AuthoringFixture.DEFINITION);

        this.handler.execute(this.request(Map.of(), new HashMap<>()));

        Mockito.verify(versions).checkout(AuthoringFixture.DEFINITION);
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
