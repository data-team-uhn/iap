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
package io.uhndata.iap.entities.models;

import java.util.Calendar;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link Entity}, including the properties it inherits from
 * {@link io.uhndata.iap.content.models.Content}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EntityTest
{
    private final SlingContext context = new SlingContext();

    private Calendar created;

    private Calendar modified;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class);
        this.created = Calendar.getInstance();
        this.created.set(2026, Calendar.JANUARY, 15, 10, 30, 0);
        this.modified = Calendar.getInstance();
        this.modified.set(2026, Calendar.FEBRUARY, 20, 14, 45, 0);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/content/entity",
            "sling:resourceType", "iap/Entity");
        assertNotNull(resource.adaptTo(Entity.class));
    }

    @Test
    void exposesEntitySpecificMetadata()
    {
        final Resource resource = this.context.create().resource("/content/entity", Map.of(
            "sling:resourceType", "iap/Entity",
            "jcr:uuid", "0b52a6f0-1e3a-4c1b-9d8e-1234567890ab",
            "jcr:lastModified", this.modified,
            "jcr:lastModifiedBy", "bob"));
        final Entity entity = resource.adaptTo(Entity.class);

        assertEquals("0b52a6f0-1e3a-4c1b-9d8e-1234567890ab", entity.getIdentifier());
        assertEquals(this.modified, entity.getLastModified());
        assertEquals("bob", entity.getLastModifiedBy());
    }

    @Test
    void inheritsContentProperties()
    {
        // The key inheritance behavior: an Entity also exposes every accessor defined on its
        // Content superclass, populated through the same adaptation.
        final Resource resource = this.context.create().resource("/content/entity", Map.of(
            "sling:resourceType", "iap/Entity",
            "jcr:created", this.created,
            "jcr:createdBy", "alice"));
        final Entity entity = resource.adaptTo(Entity.class);

        assertEquals("/content/entity", entity.getPath());
        assertEquals("entity", entity.getName());
        assertEquals("iap/Entity", entity.getType());
        assertEquals(this.created, entity.getCreated());
        assertEquals("alice", entity.getCreatedBy());
    }

    @Test
    void adaptsToParentModel()
    {
        // A node of a derived type can also be wrapped by the parent Content model, letting callers work
        // with any resource through the base model when they only need the generic content properties.
        final Resource resource = this.context.create().resource("/content/entity", Map.of(
            "sling:resourceType", "iap/Entity",
            "jcr:created", this.created,
            "jcr:createdBy", "alice"));
        final Content content = resource.adaptTo(Content.class);

        assertNotNull(content);
        // Adapting to the parent class yields the parent model, not the derived one
        assertEquals(Content.class, content.getClass());
        assertEquals("/content/entity", content.getPath());
        assertEquals("iap/Entity", content.getType());
        assertEquals(this.created, content.getCreated());
        assertEquals("alice", content.getCreatedBy());
    }

    @Test
    void toleratesMissingOptionalMetadata()
    {
        // A node with none of the optional metadata still adapts, and both the entity-specific and
        // the inherited getters return null rather than failing the adaptation.
        final Resource resource = this.context.create().resource("/content/bare",
            "sling:resourceType", "iap/Entity");
        final Entity entity = resource.adaptTo(Entity.class);

        assertNotNull(entity);
        assertNull(entity.getIdentifier());
        assertNull(entity.getLastModified());
        assertNull(entity.getLastModifiedBy());
        assertNull(entity.getCreated());
        assertNull(entity.getCreatedBy());
    }
}
