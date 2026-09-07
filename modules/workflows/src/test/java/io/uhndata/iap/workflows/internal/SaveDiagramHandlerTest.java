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

import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowConflictException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;

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
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", WorkflowVersion.State.DRAFT, Map.of());

        this.handler.execute(this.save("1-0", AuthoringFixture.upload(REPLACEMENT, "application/xml")));

        final Resource version = this.context.resourceResolver().getResource(AuthoringFixture.path("1-0"));
        assertNotNull(version);
        assertEquals(REPLACEMENT, AuthoringFixture.read(version.getChild("bpmn.xml")));
    }

    @Test
    void replacesTheDiagramADraftAlreadyHeld() throws WorkflowException, PersistenceException, IOException
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", WorkflowVersion.State.DRAFT, Map.of());
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
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", WorkflowVersion.State.DRAFT, Map.of());

        this.handler.execute(this.save("1-0", AuthoringFixture.upload(REPLACEMENT, null)));

        final Resource content = this.context.resourceResolver()
            .getResource(AuthoringFixture.path("1-0") + "/bpmn.xml/jcr:content");
        assertNotNull(content);
        assertEquals("application/xml", content.getValueMap().get("jcr:mimeType"));
    }

    @Test
    void refusesADiagramForAnythingButADraft()
    {
        // Trial, active, and retired versions are all frozen against edits, each for a different reason
        for (final WorkflowVersion.State frozen : new WorkflowVersion.State[] {
            WorkflowVersion.State.TRIAL, WorkflowVersion.State.ACTIVE, WorkflowVersion.State.RETIRED }) {
            final String name = frozen.name().toLowerCase(java.util.Locale.ROOT);
            AuthoringFixture.createVersion(this.context, name, name, frozen, Map.of());

            final WorkflowConflictException refusal = assertThrows(WorkflowConflictException.class,
                () -> this.handler.execute(this.save(name, AuthoringFixture.upload(REPLACEMENT, null))));
            assertTrue(refusal.getMessage().contains("Only a draft may be edited"));
            assertTrue(refusal.getMessage().contains("this version is " + name));
        }
    }

    @Test
    void requiresADiagramToStore()
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", WorkflowVersion.State.DRAFT, Map.of());

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(this.save("1-0", null)));
        assertTrue(refusal.getMessage().contains("bpmn.xml file is required"));
    }

    @Test
    void reportsAnUploadThatCannotBeRead()
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", WorkflowVersion.State.DRAFT, Map.of());

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(this.save("1-0", AuthoringFixture.brokenUpload())));
        assertTrue(failure.getMessage().contains("The upload broke"));
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
