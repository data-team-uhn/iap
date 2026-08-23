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

/**
 * Unit tests for {@link EntityPart}, including the properties it inherits from
 * {@link io.uhndata.iap.content.models.Content}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EntityPartTest
{
    private final SlingContext context = new SlingContext();

    private Calendar created;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class);
        this.created = Calendar.getInstance();
        this.created.set(2026, Calendar.MAY, 12, 11, 0, 0);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/content/part",
            "sling:resourceType", "iap/EntityPart");
        assertNotNull(resource.adaptTo(EntityPart.class));
    }

    @Test
    void inheritsContentProperties()
    {
        final Resource resource = this.context.create().resource("/content/part", Map.of(
            "sling:resourceType", "iap/EntityPart",
            "jcr:created", this.created,
            "jcr:createdBy", "alice"));
        final EntityPart part = resource.adaptTo(EntityPart.class);

        assertEquals("/content/part", part.getPath());
        assertEquals("part", part.getName());
        assertEquals("iap/EntityPart", part.getType());
        assertEquals(this.created, part.getCreated());
        assertEquals("alice", part.getCreatedBy());
    }

    @Test
    void adaptsToParentModel()
    {
        // A node of a derived type can also be wrapped by the parent Content model, letting callers work
        // with any resource through the base model when they only need the generic content properties.
        final Resource resource = this.context.create().resource("/content/part",
            "sling:resourceType", "iap/EntityPart");
        final Content content = resource.adaptTo(Content.class);

        assertNotNull(content);
        assertEquals(Content.class, content.getClass());
        assertEquals("iap/EntityPart", content.getType());
    }
}
