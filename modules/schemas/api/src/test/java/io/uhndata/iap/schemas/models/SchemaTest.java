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

import java.util.Calendar;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Schema}, including the properties it inherits from
 * {@link io.uhndata.iap.entities.models.Entity}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SchemaTest
{
    private final SlingContext context = new SlingContext();

    private Calendar created;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, Schema.class, SchemaVersion.class);
        this.created = Calendar.getInstance();
        this.created.set(2026, Calendar.MARCH, 10, 9, 15, 0);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema",
            "sling:resourceType", "sch/Schema");
        assertNotNull(resource.adaptTo(Schema.class));
    }

    @Test
    void exposesSchemaProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema", Map.of(
            "sling:resourceType", "sch/Schema",
            "title", "Human research schema",
            "active", true));
        final Schema schema = resource.adaptTo(Schema.class);

        assertEquals("Human research schema", schema.getTitle());
        assertTrue(schema.isActive());
    }

    @Test
    void inheritsEntityAndContentProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema", Map.of(
            "sling:resourceType", "sch/Schema",
            "jcr:uuid", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345",
            "jcr:created", this.created,
            "jcr:createdBy", "alice"));
        final Schema schema = resource.adaptTo(Schema.class);

        assertEquals("/Schemas/schema", schema.getPath());
        assertEquals("schema", schema.getName());
        assertEquals("sch/Schema", schema.getType());
        assertEquals("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345", schema.getIdentifier());
        assertEquals(this.created, schema.getCreated());
        assertEquals("alice", schema.getCreatedBy());
    }

    @Test
    void adaptsToParentModels()
    {
        // A schema node can also be wrapped by the parent models, letting callers work with it
        // through the base models when they only need the generic properties.
        final Resource resource = this.context.create().resource("/Schemas/schema", Map.of(
            "sling:resourceType", "sch/Schema",
            "jcr:uuid", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345"));

        final Entity entity = resource.adaptTo(Entity.class);
        assertNotNull(entity);
        assertEquals(Entity.class, entity.getClass());
        assertEquals("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345", entity.getIdentifier());

        final Content content = resource.adaptTo(Content.class);
        assertNotNull(content);
        assertEquals(Content.class, content.getClass());
        assertEquals("sch/Schema", content.getType());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/bare",
            "sling:resourceType", "sch/Schema");
        final Schema schema = resource.adaptTo(Schema.class);

        assertNotNull(schema);
        assertNull(schema.getTitle());
        // A missing active flag is reported as an inactive schema
        assertFalse(schema.isActive());
    }

    @Test
    void listsVersions()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema",
            "sling:resourceType", "sch/Schema");
        this.context.create().resource("/Schemas/schema/1.0", "sling:resourceType", "sch/SchemaVersion");
        this.context.create().resource("/Schemas/schema/2.0", "sling:resourceType", "sch/SchemaVersion");
        final Schema schema = resource.adaptTo(Schema.class);

        final List<SchemaVersion> versions = schema.getVersions();

        assertEquals(2, versions.size());
        assertEquals("1.0", versions.get(0).getName());
        assertEquals("2.0", versions.get(1).getName());
    }

    @Test
    void listsNoVersionsWhenNoneExist()
    {
        final Resource resource = this.context.create().resource("/Schemas/empty",
            "sling:resourceType", "sch/Schema");
        final Schema schema = resource.adaptTo(Schema.class);

        assertTrue(schema.getVersions().isEmpty());
    }

    @Test
    void findsActiveVersion()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema",
            "sling:resourceType", "sch/Schema");
        this.context.create().resource("/Schemas/schema/1.0", Map.of(
            "sling:resourceType", "sch/SchemaVersion", "active", false));
        this.context.create().resource("/Schemas/schema/2.0", Map.of(
            "sling:resourceType", "sch/SchemaVersion", "active", true));
        final Schema schema = resource.adaptTo(Schema.class);

        final SchemaVersion active = schema.getActiveVersion();

        assertNotNull(active);
        assertEquals("2.0", active.getName());
    }

    @Test
    void returnsNullActiveVersionWhenNoneIsActive()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema",
            "sling:resourceType", "sch/Schema");
        this.context.create().resource("/Schemas/schema/1.0", Map.of(
            "sling:resourceType", "sch/SchemaVersion", "active", false));
        final Schema schema = resource.adaptTo(Schema.class);

        assertNull(schema.getActiveVersion());
    }
}
