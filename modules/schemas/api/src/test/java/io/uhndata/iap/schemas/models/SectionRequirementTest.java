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
package io.uhndata.iap.schemas.models;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SectionRequirement}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SectionRequirementTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String PROTOCOL = "/Schemas/schema/1.0/protocol";

    private static final String FUNDING = PROTOCOL + "/funding";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, DocumentRequirement.class,
            SectionRequirement.class);
    }

    @Test
    void exposesSectionRequirementProperties()
    {
        final SectionRequirement section = this.context.create().resource(FUNDING, Map.of(
            TYPE, SectionRequirement.RESOURCE_TYPE,
            "label", "Funding",
            "headings", new String[] {"Funding", "Sources of support"},
            "locationPrompt", "Find where the study says who pays for it",
            "required", false)).adaptTo(SectionRequirement.class);

        assertEquals("Funding", section.getLabel());
        assertEquals(List.of("Funding", "Sources of support"), section.getHeadings());
        assertEquals("Find where the study says who pays for it", section.getLocationPrompt());
        assertFalse(section.isRequired());
    }

    @Test
    void isRequiredAndNamesNoHeadingsUnlessTheSchemaSaysOtherwise()
    {
        final SectionRequirement section = this.context.create().resource(FUNDING,
            TYPE, SectionRequirement.RESOURCE_TYPE).adaptTo(SectionRequirement.class);

        assertTrue(section.isRequired());
        assertTrue(section.getHeadings().isEmpty());
        assertNull(section.getLocationPrompt());
    }

    @Test
    void listsItsSubsections()
    {
        final Resource methods = this.context.create().resource(PROTOCOL + "/methods",
            TYPE, SectionRequirement.RESOURCE_TYPE);
        this.context.create().resource(PROTOCOL + "/methods/sampling", TYPE, SectionRequirement.RESOURCE_TYPE,
            "label", "Sampling");
        this.context.create().resource(PROTOCOL + "/methods/analysis", TYPE, SectionRequirement.RESOURCE_TYPE,
            "label", "Statistical analysis");
        this.context.create().resource(PROTOCOL + "/methods/notes", TYPE, "nt:unstructured");

        assertEquals(List.of("Sampling", "Statistical analysis"), methods.adaptTo(SectionRequirement.class)
            .getSections().stream().map(Requirement::getLabel).collect(Collectors.toList()));
    }

    @Test
    void findsItsDocumentThroughEnclosingSections()
    {
        this.context.create().resource(PROTOCOL, TYPE, DocumentRequirement.RESOURCE_TYPE);
        this.context.create().resource(PROTOCOL + "/methods", TYPE, SectionRequirement.RESOURCE_TYPE);
        final Resource analysis = this.context.create().resource(PROTOCOL + "/methods/analysis",
            TYPE, SectionRequirement.RESOURCE_TYPE);

        assertEquals(PROTOCOL, analysis.adaptTo(SectionRequirement.class).getDocument().getPath());
    }

    @Test
    void hasNoDocumentOutsideOne()
    {
        final Resource loose = this.context.create().resource("/Schemas/schema/1.0/loose",
            TYPE, SectionRequirement.RESOURCE_TYPE);

        assertNull(loose.adaptTo(SectionRequirement.class).getDocument());
    }
}
