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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Section}, including the properties it inherits from {@link QuestionnaireItem}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SectionTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Question.class, Section.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/section",
            "sling:resourceType", Section.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Section.class));
    }

    @Test
    void exposesSectionProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/section", Map.of(
            "sling:resourceType", Section.RESOURCE_TYPE,
            "title", "Study details",
            "description", "General information about the proposed study"));
        final Section section = resource.adaptTo(Section.class);

        assertEquals("Study details", section.getTitle());
        assertEquals("General information about the proposed study", section.getDescription());
    }

    @Test
    void listsChildrenByType()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/section",
            "sling:resourceType", Section.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/section/sub",
            "sling:resourceType", Section.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/section/q1",
            "sling:resourceType", Question.RESOURCE_TYPE);
        final Section section = resource.adaptTo(Section.class);

        final List<Section> subsections = section.getSections();
        assertEquals(1, subsections.size());
        assertEquals("sub", subsections.get(0).getName());

        final List<Question> questions = section.getQuestions();
        assertEquals(1, questions.size());
        assertEquals("q1", questions.get(0).getName());
    }

    @Test
    void listsNoChildrenWhenNoneExist()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/empty",
            "sling:resourceType", Section.RESOURCE_TYPE);
        final Section section = resource.adaptTo(Section.class);

        assertTrue(section.getSections().isEmpty());
        assertTrue(section.getQuestions().isEmpty());
        assertTrue(section.getChildren().isEmpty());
    }

    @Test
    void listsChildrenUsingTheSpecificModelForEach()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/section",
            "sling:resourceType", Section.RESOURCE_TYPE);
        // sling:resourceSuperType is mandatory/autocreated on sch:QuestionnaireItem in the real CND;
        // sling-mock doesn't know about the CND, so it must be set explicitly here.
        this.context.create().resource("/Schemas/schema/1.0/section/sub", Map.of(
            "sling:resourceType", Section.RESOURCE_TYPE, "sling:resourceSuperType", QuestionnaireItem.RESOURCE_TYPE,
            "title", "Nested section"));
        this.context.create().resource("/Schemas/schema/1.0/section/q1", Map.of(
            "sling:resourceType", Question.RESOURCE_TYPE, "sling:resourceSuperType", QuestionnaireItem.RESOURCE_TYPE,
            "text", "A question"));
        final Section section = resource.adaptTo(Section.class);

        final List<QuestionnaireItem> children = section.getChildren();

        assertEquals(2, children.size());
        assertEquals(Section.class, children.get(0).getClass());
        assertEquals("Nested section", ((Section) children.get(0)).getTitle());
        assertEquals(Question.class, children.get(1).getClass());
        assertEquals("A question", ((Question) children.get(1)).getText());
    }
}
