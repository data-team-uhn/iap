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
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.SectionRequirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Section}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SectionTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String REQUIREMENT_PATH = "/Schemas/study/1.0/protocol/funding";

    private static final String REQUIREMENT_ID = "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345";

    private static final String SECTION_PATH = "/Submissions/submission/protocol/v1/funding";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Section.class, Passage.class,
            Context.class, SectionRequirement.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(SECTION_PATH, TYPE, Section.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Section.class));
    }

    @Test
    void exposesTheRequirementItFulfillsAndHowSureTheModelWas() throws RepositoryException
    {
        this.context.create().resource(REQUIREMENT_PATH, TYPE, SectionRequirement.RESOURCE_TYPE, "label", "Funding");
        final Session session = Mockito.mock(Session.class);
        final Node requirement = Mockito.mock(Node.class);
        Mockito.when(requirement.getPath()).thenReturn(REQUIREMENT_PATH);
        Mockito.when(session.getNodeByIdentifier(REQUIREMENT_ID)).thenReturn(requirement);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
        final Section section = this.context.create().resource(SECTION_PATH, Map.of(
            TYPE, Section.RESOURCE_TYPE,
            "fulfills", REQUIREMENT_ID,
            "method", Section.MODEL_METHOD,
            "confidence", 0.8)).adaptTo(Section.class);

        assertEquals("Funding", section.getFulfills().getLabel());
        assertEquals(Section.MODEL_METHOD, section.getMethod());
        assertEquals(0.8, section.getConfidence());
    }

    @Test
    void exposesHowItWasFoundAndWhoChecked()
    {
        final Section section = this.context.create().resource(SECTION_PATH, Map.of(
            TYPE, Section.RESOURCE_TYPE,
            "method", Section.PICKED_HEADING_METHOD,
            "checkedBy", "submitter1")).adaptTo(Section.class);

        assertEquals(Section.PICKED_HEADING_METHOD, section.getMethod());
        assertEquals("submitter1", section.getCheckedBy());
        assertFalse(section.isPotentialDeviation());
    }

    @Test
    void listsItsPassagesInDocumentOrder()
    {
        final Resource resource = this.context.create().resource(SECTION_PATH, TYPE, Section.RESOURCE_TYPE);
        passage("p1", "4. Funding", "paid by the sponsor.");
        passage("p2", "Appendix C: Budget", "Total: $120,000");
        this.context.create().resource(SECTION_PATH + "/notes", TYPE, "nt:unstructured");
        final Section section = resource.adaptTo(Section.class);

        assertEquals(List.of("4. Funding", "Appendix C: Budget"), section.getPassages().stream()
            .map(passage -> passage.getStart().getQuote()).collect(Collectors.toList()));
        assertEquals(List.of("paid by the sponsor.", "Total: $120,000"), section.getPassages().stream()
            .map(passage -> passage.getEnd().getQuote()).collect(Collectors.toList()));
        assertTrue(section.isFound());
    }

    @Test
    void flagsTextSelectedByHandAsAPotentialDeviation()
    {
        final Section section = this.context.create().resource(SECTION_PATH,
            TYPE, Section.RESOURCE_TYPE, "method", Section.SELECTED_TEXT_METHOD).adaptTo(Section.class);

        assertTrue(section.isPotentialDeviation());
    }

    @Test
    void isNotFoundWithoutPassages()
    {
        final Section section = this.context.create().resource(SECTION_PATH, TYPE, Section.RESOURCE_TYPE)
            .adaptTo(Section.class);

        assertTrue(section.getPassages().isEmpty());
        assertFalse(section.isFound());
        assertFalse(section.isPotentialDeviation());
        assertNull(section.getFulfills());
        assertNull(section.getMethod());
        assertNull(section.getConfidence());
        assertNull(section.getCheckedBy());
    }

    /**
     * Adds a passage to the section, quoting its first and last words.
     *
     * @param name the passage's node name
     * @param start its first words
     * @param end its last words
     */
    private void passage(final String name, final String start, final String end)
    {
        final String path = SECTION_PATH + "/" + name;
        this.context.create().resource(path, TYPE, Passage.RESOURCE_TYPE);
        this.context.create().resource(path + "/start", TYPE, Context.RESOURCE_TYPE, "quote", start);
        this.context.create().resource(path + "/end", TYPE, Context.RESOURCE_TYPE, "quote", end);
    }
}
