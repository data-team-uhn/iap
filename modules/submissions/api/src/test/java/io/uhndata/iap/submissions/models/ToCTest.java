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

import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ToC}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ToCTest
{
    private static final String TOC_PATH = "/Submissions/submission/consent/v0/file/toc";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ToC.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(TOC_PATH, "sling:resourceType", ToC.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(ToC.class));
    }

    @Test
    void exposesTheOutline()
    {
        final Resource resource = this.context.create().resource(TOC_PATH, Map.of(
            "sling:resourceType", ToC.RESOURCE_TYPE,
            "source", "md-toc",
            "titles", new String[]{ "Background", "Methods", "Recruitment" },
            "startLine", 12L,
            "endLine", 34L));
        final ToC toc = resource.adaptTo(ToC.class);

        assertEquals("md-toc", toc.getSource());
        assertEquals(List.of("Background", "Methods", "Recruitment"), toc.getTitles());
        assertEquals(12L, toc.getStartLine());
        assertEquals(34L, toc.getEndLine());
    }

    @Test
    void hasNoLineRangeWhenTheOutlineWasNotPrinted()
    {
        // Bookmarks give an outline with no printed table of contents to skip over
        final Resource resource = this.context.create().resource(TOC_PATH, Map.of(
            "sling:resourceType", ToC.RESOURCE_TYPE,
            "source", "pdf-bookmarks",
            "titles", new String[]{ "Background" }));
        final ToC toc = resource.adaptTo(ToC.class);

        assertEquals("pdf-bookmarks", toc.getSource());
        assertNull(toc.getStartLine());
        assertNull(toc.getEndLine());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource(TOC_PATH, "sling:resourceType", ToC.RESOURCE_TYPE);
        final ToC toc = resource.adaptTo(ToC.class);

        assertNotNull(toc);
        assertNull(toc.getSource());
        assertTrue(toc.getTitles().isEmpty());
        assertNull(toc.getStartLine());
        assertNull(toc.getEndLine());
    }
}
