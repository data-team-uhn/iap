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
package io.uhndata.iap.items.models;

import java.util.Calendar;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ItemModel}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ItemModelTest
{
    private final SlingContext context = new SlingContext();

    private Calendar created;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(ItemModel.class);
        this.created = Calendar.getInstance();
        this.created.set(2026, Calendar.JANUARY, 15, 10, 30, 0);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/content/item",
            "sling:resourceType", "iap/Item");
        assertNotNull(resource.adaptTo(ItemModel.class));
    }

    @Test
    void exposesResourceDerivedProperties()
    {
        final Resource resource = this.context.create().resource("/content/item",
            "sling:resourceType", "iap/Item");
        final ItemModel item = resource.adaptTo(ItemModel.class);

        assertEquals("/content/item", item.getPath());
        assertEquals("item", item.getName());
        assertEquals("iap/Item", item.getType());
    }

    @Test
    void exposesCreationMetadata()
    {
        final Resource resource = this.context.create().resource("/content/item", Map.of(
            "sling:resourceType", "iap/Item",
            "jcr:created", this.created,
            "jcr:createdBy", "alice"));
        final ItemModel item = resource.adaptTo(ItemModel.class);

        assertEquals(this.created, item.getCreated());
        assertEquals("alice", item.getCreatedBy());
    }

    @Test
    void toleratesMissingOptionalMetadata()
    {
        // A node without the optional jcr:created / jcr:createdBy still adapts, thanks to the
        // OPTIONAL injection strategy, and the corresponding getters return null.
        final Resource resource = this.context.create().resource("/content/bare",
            "sling:resourceType", "iap/Item");
        final ItemModel item = resource.adaptTo(ItemModel.class);

        assertNotNull(item);
        assertNull(item.getCreated());
        assertNull(item.getCreatedBy());
    }
}
