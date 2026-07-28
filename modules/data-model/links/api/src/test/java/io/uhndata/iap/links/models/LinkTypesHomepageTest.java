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
package io.uhndata.iap.links.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LinkTypesHomepage}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LinkTypesHomepageTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, LinkDefinition.class, LinkTypesHomepage.class);
    }

    @Test
    void listsTheDefinedLinkTypes()
    {
        final Resource resource = this.context.create().resource("/LinkTypes",
            "sling:resourceType", LinkTypesHomepage.RESOURCE_TYPE);
        this.context.create().resource("/LinkTypes/references",
            "sling:resourceType", LinkDefinition.RESOURCE_TYPE);
        this.context.create().resource("/LinkTypes/ehrChart",
            "sling:resourceType", LinkDefinition.RESOURCE_TYPE);
        final LinkTypesHomepage homepage = resource.adaptTo(LinkTypesHomepage.class);

        assertNotNull(homepage);
        assertEquals(2, homepage.getDefinitions().size());
        assertEquals("references", homepage.getDefinitions().get(0).getLabel());
    }

    @Test
    void listsNothingWhenEmpty()
    {
        final Resource resource = this.context.create().resource("/LinkTypes",
            "sling:resourceType", LinkTypesHomepage.RESOURCE_TYPE);

        assertTrue(resource.adaptTo(LinkTypesHomepage.class).getDefinitions().isEmpty());
    }
}
