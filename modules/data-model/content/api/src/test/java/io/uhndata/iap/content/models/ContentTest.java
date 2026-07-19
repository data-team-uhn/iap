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
package io.uhndata.iap.content.models;

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
 * Unit tests for {@link Content}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ContentTest
{
    private final SlingContext context = new SlingContext();

    private Calendar created;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class);
        this.created = Calendar.getInstance();
        this.created.set(2026, Calendar.JANUARY, 15, 10, 30, 0);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/content/sample",
            "sling:resourceType", "iap/Content");
        assertNotNull(resource.adaptTo(Content.class));
    }

    @Test
    void exposesResourceDerivedProperties()
    {
        final Resource resource = this.context.create().resource("/content/sample",
            "sling:resourceType", "iap/Content");
        final Content content = resource.adaptTo(Content.class);

        assertEquals("/content/sample", content.getPath());
        assertEquals("sample", content.getName());
        assertEquals("iap/Content", content.getType());
    }

    @Test
    void exposesCreationMetadata()
    {
        final Resource resource = this.context.create().resource("/content/sample", Map.of(
            "sling:resourceType", "iap/Content",
            "jcr:created", this.created,
            "jcr:createdBy", "alice"));
        final Content content = resource.adaptTo(Content.class);

        assertEquals(this.created, content.getCreated());
        assertEquals("alice", content.getCreatedBy());
    }

    @Test
    void toleratesMissingOptionalMetadata()
    {
        // A node without the optional jcr:created / jcr:createdBy still adapts, thanks to the
        // OPTIONAL injection strategy, and the corresponding getters return null.
        final Resource resource = this.context.create().resource("/content/bare",
            "sling:resourceType", "iap/Content");
        final Content content = resource.adaptTo(Content.class);

        assertNotNull(content);
        assertNull(content.getCreated());
        assertNull(content.getCreatedBy());
    }
}
