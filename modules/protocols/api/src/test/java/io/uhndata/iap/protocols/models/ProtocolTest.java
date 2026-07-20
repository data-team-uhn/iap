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
package io.uhndata.iap.protocols.models;

import java.util.Calendar;
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
 * Unit tests for {@link Protocol}, including the properties it inherits from
 * {@link io.uhndata.iap.entities.models.Entity}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ProtocolTest
{
    private final SlingContext context = new SlingContext();

    private Calendar created;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, Protocol.class);
        this.created = Calendar.getInstance();
        this.created.set(2026, Calendar.MARCH, 10, 9, 15, 0);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Protocols/protocol",
            "sling:resourceType", "pt/Protocol");
        assertNotNull(resource.adaptTo(Protocol.class));
    }

    @Test
    void exposesProtocolProperties()
    {
        final Resource resource = this.context.create().resource("/Protocols/protocol", Map.of(
            "sling:resourceType", "pt/Protocol",
            "title", "Human research protocol",
            "active", true));
        final Protocol protocol = resource.adaptTo(Protocol.class);

        assertEquals("Human research protocol", protocol.getTitle());
        assertTrue(protocol.isActive());
    }

    @Test
    void inheritsEntityAndContentProperties()
    {
        final Resource resource = this.context.create().resource("/Protocols/protocol", Map.of(
            "sling:resourceType", "pt/Protocol",
            "jcr:uuid", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345",
            "jcr:created", this.created,
            "jcr:createdBy", "alice"));
        final Protocol protocol = resource.adaptTo(Protocol.class);

        assertEquals("/Protocols/protocol", protocol.getPath());
        assertEquals("protocol", protocol.getName());
        assertEquals("pt/Protocol", protocol.getType());
        assertEquals("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345", protocol.getIdentifier());
        assertEquals(this.created, protocol.getCreated());
        assertEquals("alice", protocol.getCreatedBy());
    }

    @Test
    void adaptsToParentModels()
    {
        // A protocol node can also be wrapped by the parent models, letting callers work with it
        // through the base models when they only need the generic properties.
        final Resource resource = this.context.create().resource("/Protocols/protocol", Map.of(
            "sling:resourceType", "pt/Protocol",
            "jcr:uuid", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345"));

        final Entity entity = resource.adaptTo(Entity.class);
        assertNotNull(entity);
        assertEquals(Entity.class, entity.getClass());
        assertEquals("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345", entity.getIdentifier());

        final Content content = resource.adaptTo(Content.class);
        assertNotNull(content);
        assertEquals(Content.class, content.getClass());
        assertEquals("pt/Protocol", content.getType());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource("/Protocols/bare",
            "sling:resourceType", "pt/Protocol");
        final Protocol protocol = resource.adaptTo(Protocol.class);

        assertNotNull(protocol);
        assertNull(protocol.getTitle());
        // A missing active flag is reported as an inactive protocol
        assertFalse(protocol.isActive());
    }
}
