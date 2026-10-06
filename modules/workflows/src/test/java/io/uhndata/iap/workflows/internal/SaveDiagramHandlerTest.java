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

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SaveDiagramHandler}: storing a diagram on a draft, replacing whatever it held, and
 * refusing one for a version something could be following.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SaveDiagramHandlerTest
{
    private static final String REPLACEMENT = "<bpmn:definitions id=\"two\"/>";

    private final SlingContext context = new SlingContext();

    private final SaveDiagramHandler handler = new SaveDiagramHandler();

    private Activity activity;

    @BeforeEach
    void setUp()
    {
        AuthoringFixture.setUp(this.context);
        this.activity = AuthoringFixture.activity(this.context, "save",
            Map.of("handler", SaveDiagramHandler.NAME));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(SaveDiagramHandler.NAME, this.handler.getName());
    }

    @Test
    void storesADiagramOnADraftThatHadNone() throws WorkflowException, PersistenceException, IOException
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", "draft", Map.of());

        this.handler.execute(this.save("1-0", AuthoringFixture.upload(REPLACEMENT, "application/xml")));

        final Resource version = this.context.resourceResolver().getResource(AuthoringFixture.path("1-0"));
        assertNotNull(version);
        assertEquals(REPLACEMENT, AuthoringFixture.read(version.getChild("bpmn.xml")));
    }

    @Test
    void replacesTheDiagramADraftAlreadyHeld() throws WorkflowException, PersistenceException, IOException
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", "draft", Map.of());
        AuthoringFixture.loadDiagram(this.context, "1-0");
        final Resource version = this.context.resourceResolver().getResource(AuthoringFixture.path("1-0"));
        assertNotNull(version);
        final String fileId = version.getChild("bpmn.xml").getPath();

        this.handler.execute(this.save("1-0", AuthoringFixture.upload(REPLACEMENT, "text/xml")));

        assertEquals(REPLACEMENT, AuthoringFixture.read(version.getChild("bpmn.xml")));
        assertEquals("text/xml", version.getChild("bpmn.xml/jcr:content").getValueMap().get("jcr:mimeType"));
        // The file node is reused across a save, so a diagram keeps its identity
        assertEquals(fileId, version.getChild("bpmn.xml").getPath());
    }

    @Test
    void fallsBackToXmlWhenTheUploadDeclaresNoType() throws WorkflowException, PersistenceException
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", "draft", Map.of());

        this.handler.execute(this.save("1-0", AuthoringFixture.upload(REPLACEMENT, null)));

        final Resource content = this.context.resourceResolver()
            .getResource(AuthoringFixture.path("1-0") + "/bpmn.xml/jcr:content");
        assertNotNull(content);
        assertEquals("application/xml", content.getValueMap().get("jcr:mimeType"));
    }

    @Test
    void refusesADiagramForAVersionStoredOutsideAHomepage()
    {
        // Everything that lists workflows starts from the homepages that hold them, so a version kept anywhere
        // else could be given a diagram and then never be found again
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", "draft", Map.of());
        final Resource homepage = this.context.resourceResolver().getResource("/Workflows");
        assertNotNull(homepage);
        homepage.adaptTo(ModifiableValueMap.class).remove("childNodeType");

        final WorkflowDefinitionException refusal = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(this.save("1-0", AuthoringFixture.upload(REPLACEMENT, null))));
        assertTrue(refusal.getMessage().contains("is not stored in a homepage that holds workflows"));
    }

    @Test
    void refusesATargetThatIsNotAVersion()
    {
        // A definition that sends the save of anything but a version here is the mistake, not the request
        AuthoringFixture.createVersion(this.context, "2-0", "2.0", "draft", Map.of());
        final WorkflowTaskContextImpl request = AuthoringFixture.context(
            AuthoringFixture.unreadable(this.context, AuthoringFixture.path("2-0")), "save",
            Map.of(VersionEdits.BPMN_FILE, AuthoringFixture.upload(REPLACEMENT, null)), this.activity, new HashMap<>());

        final WorkflowDefinitionException refusal = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(request));
        assertTrue(refusal.getMessage().contains("acts on workflow versions"));
    }

    @Test
    void requiresADiagramToStore()
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", "draft", Map.of());

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(this.save("1-0", null)));
        assertTrue(refusal.getMessage().contains("bpmn.xml file is required"));
    }

    @Test
    void reportsAnUploadThatCannotBeRead()
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", "draft", Map.of());

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(this.save("1-0", AuthoringFixture.brokenUpload())));
        assertTrue(failure.getMessage().contains("The upload broke"));
    }

    @Test
    void checksOutTheVersionBeforeWriting() throws WorkflowException, PersistenceException, RepositoryException
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", "draft", Map.of());
        final VersionManager versions = AuthoringFixture.checkedIn(this.context, AuthoringFixture.path("1-0"));

        this.handler.execute(this.save("1-0", AuthoringFixture.upload(REPLACEMENT, "application/xml")));

        Mockito.verify(versions).checkout(AuthoringFixture.path("1-0"));
    }

    /**
     * A task context saving a diagram onto one of the fixture's versions.
     *
     * @param name the version's node name
     * @param diagram the uploaded document, or {@code null} to send none
     * @return the assembled context
     */
    private WorkflowTaskContextImpl save(final String name, final EventAttachment diagram)
    {
        final Map<String, Object> payload = new HashMap<>();
        if (diagram != null) {
            payload.put("bpmn.xml", diagram);
        }
        return AuthoringFixture.context(this.context.resourceResolver().getResource(AuthoringFixture.path(name)),
            "save", payload, this.activity, new HashMap<>());
    }
}
