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
 * Unit tests for {@link FormRequirement}, including the properties it inherits from {@link Requirement}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FormRequirementTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Question.class, Section.class,
            SingleCondition.class, FormRequirement.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/form",
            "sling:resourceType", FormRequirement.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(FormRequirement.class));
    }

    @Test
    void inheritsRequirementProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/form", Map.of(
            "sling:resourceType", FormRequirement.RESOURCE_TYPE,
            "label", "Application form"));
        final FormRequirement requirement = resource.adaptTo(FormRequirement.class);

        assertEquals("Application form", requirement.getLabel());
    }

    @Test
    void listsSectionsAndQuestions()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/form",
            "sling:resourceType", FormRequirement.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/form/section1",
            "sling:resourceType", Section.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/form/q1",
            "sling:resourceType", Question.RESOURCE_TYPE);
        final FormRequirement requirement = resource.adaptTo(FormRequirement.class);

        assertEquals(1, requirement.getSections().size());
        assertEquals("section1", requirement.getSections().get(0).getName());
        assertEquals(1, requirement.getQuestions().size());
        assertEquals("q1", requirement.getQuestions().get(0).getName());
    }

    @Test
    void listsChildrenUsingTheSpecificModelForEach()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/form",
            "sling:resourceType", FormRequirement.RESOURCE_TYPE);
        // sling:resourceSuperType is mandatory/autocreated on sch:FormItem in the real CND;
        // sling-mock doesn't know about the CND, so it must be set explicitly here.
        this.context.create().resource("/Schemas/schema/1.0/form/section1", Map.of(
            "sling:resourceType", Section.RESOURCE_TYPE, "sling:resourceSuperType", FormItem.RESOURCE_TYPE,
            "title", "Study details"));
        this.context.create().resource("/Schemas/schema/1.0/form/q1", Map.of(
            "sling:resourceType", Question.RESOURCE_TYPE, "sling:resourceSuperType", FormItem.RESOURCE_TYPE,
            "text", "Does this involve human subjects?"));
        // Inherited from Requirement: not a FormItem, excluded from getChildren()
        this.context.create().resource("/Schemas/schema/1.0/form/c1",
            "sling:resourceType", SingleCondition.RESOURCE_TYPE);
        final FormRequirement requirement = resource.adaptTo(FormRequirement.class);

        final List<FormItem> children = requirement.getChildren();

        assertEquals(2, children.size());
        assertEquals(Section.class, children.get(0).getClass());
        assertEquals("Study details", ((Section) children.get(0)).getTitle());
        assertEquals(Question.class, children.get(1).getClass());
        assertEquals("Does this involve human subjects?", ((Question) children.get(1)).getText());
    }

    @Test
    void listsNoChildrenWhenNoneExist()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/empty",
            "sling:resourceType", FormRequirement.RESOURCE_TYPE);
        final FormRequirement requirement = resource.adaptTo(FormRequirement.class);

        assertTrue(requirement.getChildren().isEmpty());
    }
}
