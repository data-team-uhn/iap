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
package io.uhndata.iap.submissions.internal.handlers;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.utils.testing.Referenceables;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.testing.WorkflowResourceTypes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GenerateDocumentStepCheck}: a generated document has to be filled in by a step of the
 * schema's workflow.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class GenerateDocumentStepCheckTest
{
    private static final String TYPE = "sling:resourceType";

    // See AttachDocumentHandlerTest: a mock repository has no /libs/sch hierarchy to inherit from
    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String VERSION_PATH = "/Schemas/ethics/v2";

    private static final String LETTER_PATH = VERSION_PATH + "/decisionLetter";

    private static final String WORKFLOW_PATH = "/Workflows/ethics/v1";

    // JCR-backed, so that the schema version's workflow is a real REFERENCE
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final GenerateDocumentStepCheck check = new GenerateDocumentStepCheck();

    private Resource version;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, DocumentRequirement.class);
        this.context.addModelsForPackage("io.uhndata.iap.workflows.models");
        WorkflowResourceTypes.installIn(this.context);
        this.context.create().resource("/Schemas/ethics", Map.of(TYPE, Schema.RESOURCE_TYPE, "title", "Ethics"));
        this.version = this.context.create().resource(VERSION_PATH, Map.of(TYPE, SchemaVersion.RESOURCE_TYPE));
        this.context.create().resource(LETTER_PATH, Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Decision letter",
            "generated", true));
        this.context.create().resource(VERSION_PATH + "/consent", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Consent form"));
        this.context.create().resource(WORKFLOW_PATH, Map.of(TYPE, WorkflowVersion.RESOURCE_TYPE));
        final Referenceables references = new Referenceables(this.context.resourceResolver());
        references.identify(WORKFLOW_PATH);
        references.setReference(VERSION_PATH, "workflow", WORKFLOW_PATH);
    }

    @Test
    void appliesToSchemaVersionsOnly()
    {
        assertTrue(this.check.appliesTo(this.version));
        assertFalse(this.check.appliesTo(this.context.resourceResolver().getResource(WORKFLOW_PATH)));
    }

    @Test
    void passesAGeneratedDocumentThatAStepNamesByName()
    {
        step("generate", "decisionLetter");

        assertEquals(List.of(), this.check.check(this.version));
    }

    @Test
    void passesAGeneratedDocumentThatAStepNamesByPath()
    {
        step("generate", LETTER_PATH);

        assertEquals(List.of(), this.check.check(this.version));
    }

    @Test
    void refusesAGeneratedDocumentNoStepFillsIn()
    {
        // A step for another requirement, and the preview's, which names none
        step("generateOther", "consent");
        step("preview", null);

        assertEquals(List.of("\"Decision letter\" is generated, but no generateDocument step of the workflow names it,"
            + " so it would never be filled in"), this.check.check(this.version));
    }

    @Test
    void refusesAGeneratedDocumentWhenThereIsNoWorkflow()
    {
        this.version.adaptTo(ModifiableValueMap.class).remove("workflow");

        assertEquals(List.of("\"Decision letter\" is generated, but there is no workflow to generate it"),
            this.check.check(this.version));
    }

    @Test
    void hasNothingToSayAboutASchemaThatGeneratesNothing()
    {
        this.context.resourceResolver().getResource(LETTER_PATH).adaptTo(ModifiableValueMap.class)
            .put("generated", false);

        assertEquals(List.of(), this.check.check(this.version));
    }

    private void step(final String id, final String requirement)
    {
        final Map<String, Object> properties = requirement == null
            ? Map.of(TYPE, Activity.RESOURCE_TYPE, "elementId", id, "handler", GenerateDocumentHandler.HANDLER_NAME)
            : Map.of(TYPE, Activity.RESOURCE_TYPE, "elementId", id, "handler", GenerateDocumentHandler.HANDLER_NAME,
                GenerateDocumentHandler.REQUIREMENT_PARAMETER, requirement);
        this.context.create().resource(WORKFLOW_PATH + "/" + id, properties);
    }
}
