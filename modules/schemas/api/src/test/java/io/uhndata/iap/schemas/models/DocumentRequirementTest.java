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
package io.uhndata.iap.schemas.models;

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link DocumentRequirement}, including the properties it inherits from {@link Requirement}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DocumentRequirementTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Requirement.class,
            DocumentRequirement.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/consent",
            "sling:resourceType", DocumentRequirement.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(DocumentRequirement.class));
    }

    @Test
    void exposesDocumentRequirementProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/consent", Map.of(
            "sling:resourceType", DocumentRequirement.RESOURCE_TYPE,
            "label", "Patient consent",
            "acceptedFileTypes", new String[]{ "application/pdf" },
            "aiCheckPrompt", "Confirm the document is signed and dated"));
        final DocumentRequirement requirement = resource.adaptTo(DocumentRequirement.class);

        assertEquals("Patient consent", requirement.getLabel());
        assertArrayEquals(new String[]{ "application/pdf" }, requirement.getAcceptedFileTypes());
        assertEquals("Confirm the document is signed and dated", requirement.getAiCheckPrompt());
    }

    @Test
    void exposesTemplateWhenPresent()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/consent",
            "sling:resourceType", DocumentRequirement.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/consent/template",
            "sling:resourceType", "nt:file");
        final DocumentRequirement requirement = resource.adaptTo(DocumentRequirement.class);

        assertNotNull(requirement.getTemplate());
        assertEquals("template", requirement.getTemplate().getName());
    }

    @Test
    void toleratesMissingTemplate()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/consent",
            "sling:resourceType", DocumentRequirement.RESOURCE_TYPE);
        final DocumentRequirement requirement = resource.adaptTo(DocumentRequirement.class);

        assertNull(requirement.getTemplate());
    }
}
