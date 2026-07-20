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
 * Unit tests for {@link Requirement}, exercised through the {@link ApprovalRequirement} concrete subtype (an
 * abstract node type has no direct instances of its own).
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RequirementTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Conditional.class, ConditionalGroup.class,
            Requirement.class, ApprovalRequirement.class);
    }

    @Test
    void exposesRequirementProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req", Map.of(
            "sling:resourceType", ApprovalRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", Requirement.RESOURCE_TYPE,
            "label", "REB approval",
            "description", "Approval from the Research Ethics Board",
            "required", true));
        final Requirement requirement = resource.adaptTo(Requirement.class);

        assertEquals("REB approval", requirement.getLabel());
        assertEquals("Approval from the Research Ethics Board", requirement.getDescription());
        assertTrue(requirement.isRequired());
    }

    @Test
    void adaptsGenericallyAcrossSubtypes()
    {
        // A concrete subtype's resource still adapts to the abstract Requirement model, exposing only
        // the common properties, same as adapting an Entity subtype to Entity.
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req", Map.of(
            "sling:resourceType", ApprovalRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", Requirement.RESOURCE_TYPE,
            "label", "REB approval"));
        final Requirement requirement = resource.adaptTo(Requirement.class);

        assertNotNull(requirement);
        assertEquals(Requirement.class, requirement.getClass());
        assertEquals("REB approval", requirement.getLabel());
    }

    @Test
    void listsConditionsControllingApplicability()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req", Map.of(
            "sling:resourceType", ApprovalRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", Requirement.RESOURCE_TYPE));
        this.context.create().resource("/Schemas/schema/1.0/req/c1",
            "sling:resourceType", Conditional.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/req/g1",
            "sling:resourceType", ConditionalGroup.RESOURCE_TYPE);
        final Requirement requirement = resource.adaptTo(Requirement.class);

        assertEquals(1, requirement.getConditionals().size());
        assertEquals(1, requirement.getConditionalGroups().size());
    }

    @Test
    void listsConditionsUsingTheSpecificModelForEach()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req", Map.of(
            "sling:resourceType", ApprovalRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", Requirement.RESOURCE_TYPE));
        // sling:resourceSuperType is mandatory/autocreated on sch:Condition in the real CND; sling-mock
        // doesn't know about the CND, so it must be set explicitly here.
        this.context.create().resource("/Schemas/schema/1.0/req/c1", Map.of(
            "sling:resourceType", Conditional.RESOURCE_TYPE, "sling:resourceSuperType", Condition.RESOURCE_TYPE,
            "comparator", "equals"));
        this.context.create().resource("/Schemas/schema/1.0/req/g1", Map.of(
            "sling:resourceType", ConditionalGroup.RESOURCE_TYPE, "sling:resourceSuperType", Condition.RESOURCE_TYPE,
            "requireAll", true));
        final Requirement requirement = resource.adaptTo(Requirement.class);

        final List<Condition> conditions = requirement.getConditions();

        assertEquals(2, conditions.size());
        assertEquals(Conditional.class, conditions.get(0).getClass());
        assertEquals("equals", ((Conditional) conditions.get(0)).getComparator());
        assertEquals(ConditionalGroup.class, conditions.get(1).getClass());
        assertTrue(((ConditionalGroup) conditions.get(1)).isRequireAll());
    }
}
