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
package io.uhndata.iap.resources.models;

import java.util.Calendar;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.items.models.ItemModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ResourceModel}, including the properties it inherits from
 * {@link io.uhndata.iap.items.models.ItemModel}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ResourceModelTest
{
    private final SlingContext context = new SlingContext();

    private Calendar created;

    private Calendar modified;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(ItemModel.class, ResourceModel.class);
        this.created = Calendar.getInstance();
        this.created.set(2026, Calendar.JANUARY, 15, 10, 30, 0);
        this.modified = Calendar.getInstance();
        this.modified.set(2026, Calendar.FEBRUARY, 20, 14, 45, 0);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/content/entity",
            "sling:resourceType", "iap/Resource");
        assertNotNull(resource.adaptTo(ResourceModel.class));
    }

    @Test
    void exposesResourceSpecificMetadata()
    {
        final Resource resource = this.context.create().resource("/content/entity", Map.of(
            "sling:resourceType", "iap/Resource",
            "jcr:uuid", "0b52a6f0-1e3a-4c1b-9d8e-1234567890ab",
            "jcr:lastModified", this.modified,
            "jcr:lastModifiedBy", "bob"));
        final ResourceModel entity = resource.adaptTo(ResourceModel.class);

        assertEquals("0b52a6f0-1e3a-4c1b-9d8e-1234567890ab", entity.getIdentifier());
        assertEquals(this.modified, entity.getLastModified());
        assertEquals("bob", entity.getLastModifiedBy());
    }

    @Test
    void inheritsItemProperties()
    {
        // The key inheritance behavior: a ResourceModel also exposes every accessor defined on its
        // ItemModel superclass, populated through the same adaptation.
        final Resource resource = this.context.create().resource("/content/entity", Map.of(
            "sling:resourceType", "iap/Resource",
            "jcr:created", this.created,
            "jcr:createdBy", "alice"));
        final ResourceModel entity = resource.adaptTo(ResourceModel.class);

        assertEquals("/content/entity", entity.getPath());
        assertEquals("entity", entity.getName());
        assertEquals("iap/Resource", entity.getType());
        assertEquals(this.created, entity.getCreated());
        assertEquals("alice", entity.getCreatedBy());
    }

    @Test
    void adaptsToParentModel()
    {
        // A node of a derived type can also be wrapped by the parent ItemModel, letting callers work
        // with any resource through the base model when they only need the generic item properties.
        final Resource resource = this.context.create().resource("/content/entity", Map.of(
            "sling:resourceType", "iap/Resource",
            "jcr:created", this.created,
            "jcr:createdBy", "alice"));
        final ItemModel item = resource.adaptTo(ItemModel.class);

        assertNotNull(item);
        // Adapting to the parent class yields the parent model, not the derived one
        assertEquals(ItemModel.class, item.getClass());
        assertEquals("/content/entity", item.getPath());
        assertEquals("iap/Resource", item.getType());
        assertEquals(this.created, item.getCreated());
        assertEquals("alice", item.getCreatedBy());
    }

    @Test
    void toleratesMissingOptionalMetadata()
    {
        // A node with none of the optional metadata still adapts, and both the resource-specific and
        // the inherited getters return null rather than failing the adaptation.
        final Resource resource = this.context.create().resource("/content/bare",
            "sling:resourceType", "iap/Resource");
        final ResourceModel entity = resource.adaptTo(ResourceModel.class);

        assertNotNull(entity);
        assertNull(entity.getIdentifier());
        assertNull(entity.getLastModified());
        assertNull(entity.getLastModifiedBy());
        assertNull(entity.getCreated());
        assertNull(entity.getCreatedBy());
    }
}
