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
package io.uhndata.iap.submissions.models;

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link Context}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ContextTest
{
    private static final String CONTEXT_PATH = "/Submissions/submission/answer/context0";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Context.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(CONTEXT_PATH,
            "sling:resourceType", Context.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Context.class));
    }

    @Test
    void exposesItsProperties()
    {
        final Resource resource = this.context.create().resource(CONTEXT_PATH, Map.of(
            "sling:resourceType", Context.RESOURCE_TYPE,
            "quote", "42 participants will be recruited",
            "header", "3.2 Recruitment",
            "page", 7L));
        final Context quoted = resource.adaptTo(Context.class);

        assertEquals("42 participants will be recruited", quoted.getQuote());
        assertEquals("3.2 Recruitment", quoted.getHeader());
        assertEquals(7L, quoted.getPage());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource(CONTEXT_PATH,
            "sling:resourceType", Context.RESOURCE_TYPE);
        final Context quoted = resource.adaptTo(Context.class);

        assertNotNull(quoted);
        assertNull(quoted.getQuote());
        assertNull(quoted.getHeader());
        // A quote from a source with no page markers, e.g. anything that came in as DOCX
        assertNull(quoted.getPage());
    }
}
