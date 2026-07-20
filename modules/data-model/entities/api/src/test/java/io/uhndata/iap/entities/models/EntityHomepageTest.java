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
 * Unit tests for {@link EntityHomepage}, including the properties it inherits from
 * {@link io.uhndata.iap.content.models.Content}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EntityHomepageTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityHomepage.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas",
            "sling:resourceType", "iap/EntityHomepage");
        assertNotNull(resource.adaptTo(EntityHomepage.class));
    }

    @Test
    void inheritsContentProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas", Map.of(
            "sling:resourceType", "iap/EntityHomepage",
            "jcr:createdBy", "alice"));
        final EntityHomepage homepage = resource.adaptTo(EntityHomepage.class);

        assertEquals("/Schemas", homepage.getPath());
        assertEquals("Schemas", homepage.getName());
        assertEquals("iap/EntityHomepage", homepage.getType());
        assertEquals("alice", homepage.getCreatedBy());
    }

    @Test
    void adaptsToParentModel()
    {
        final Resource resource = this.context.create().resource("/Schemas",
            "sling:resourceType", "iap/EntityHomepage");
        final Content content = resource.adaptTo(Content.class);

        assertNotNull(content);
        assertEquals(Content.class, content.getClass());
        assertEquals("iap/EntityHomepage", content.getType());
    }
}
