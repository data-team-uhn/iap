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

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SchemaVersion}, including the properties it inherits from
 * {@link io.uhndata.iap.entities.models.Entity}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SchemaVersionTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Requirement.class,
            FormRequirement.class, DocumentRequirement.class, ApprovalRequirement.class,
            SchemaVersion.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0",
            "sling:resourceType", SchemaVersion.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(SchemaVersion.class));
    }

    @Test
    void exposesSchemaVersionProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0", Map.of(
            "sling:resourceType", SchemaVersion.RESOURCE_TYPE,
            "version", "1.0",
            "description", "Initial version",
            "active", true,
            "workflow", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345"));
        final SchemaVersion version = resource.adaptTo(SchemaVersion.class);

        assertEquals("1.0", version.getVersion());
        assertEquals("Initial version", version.getDescription());
        assertTrue(version.isActive());
        assertEquals("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345", version.getWorkflow());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/bare",
            "sling:resourceType", SchemaVersion.RESOURCE_TYPE);
        final SchemaVersion version = resource.adaptTo(SchemaVersion.class);

        assertNotNull(version);
        assertNull(version.getVersion());
        assertNull(version.getWorkflow());
        assertFalse(version.isActive());
    }

    @Test
    void listsRequirementsGenericallyAndByType()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0",
            "sling:resourceType", SchemaVersion.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/form", Map.of(
            "sling:resourceType", FormRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", Requirement.RESOURCE_TYPE));
        this.context.create().resource("/Schemas/schema/1.0/consent", Map.of(
            "sling:resourceType", DocumentRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", Requirement.RESOURCE_TYPE));
        this.context.create().resource("/Schemas/schema/1.0/reb", Map.of(
            "sling:resourceType", ApprovalRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", Requirement.RESOURCE_TYPE));
        final SchemaVersion version = resource.adaptTo(SchemaVersion.class);

        final List<Requirement> all = version.getRequirements();
        assertEquals(3, all.size());

        assertEquals(1, version.getFormRequirements().size());
        assertEquals("form", version.getFormRequirements().get(0).getName());
        assertEquals(1, version.getDocumentRequirements().size());
        assertEquals("consent", version.getDocumentRequirements().get(0).getName());
        assertEquals(1, version.getApprovalRequirements().size());
        assertEquals("reb", version.getApprovalRequirements().get(0).getName());
    }
}
