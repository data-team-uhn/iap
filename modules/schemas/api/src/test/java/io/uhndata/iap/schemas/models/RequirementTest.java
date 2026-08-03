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

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.conditions.models.Condition;
import io.uhndata.iap.conditions.models.ConditionGroup;
import io.uhndata.iap.conditions.models.SingleCondition;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        this.context.addModelsForClasses(Content.class, EntityPart.class, SingleCondition.class, ConditionGroup.class,
            ApprovalRequirement.class);
    }

    @Test
    void exposesRequirementProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req", Map.of(
            "sling:resourceType", ApprovalRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", Requirement.RESOURCE_TYPE,
            "label", "REB approval",
            "description", "Approval from the Research Ethics Board"));
        final Requirement requirement = resource.adaptTo(Requirement.class);

        assertEquals("REB approval", requirement.getLabel());
        assertEquals("Approval from the Research Ethics Board", requirement.getDescription());
    }

    @Test
    void dispatchesToConcreteSubtype()
    {
        // Adapting a concrete subtype's resource to the abstract Requirement model dispatches to the real
        // concrete class, exposing its own fields too, not just the common ones.
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req", Map.of(
            "sling:resourceType", ApprovalRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", Requirement.RESOURCE_TYPE,
            "label", "REB approval",
            "approverGroup", "reb-members"));
        final Requirement requirement = resource.adaptTo(Requirement.class);

        assertNotNull(requirement);
        assertEquals(ApprovalRequirement.class, requirement.getClass());
        assertEquals("REB approval", requirement.getLabel());
        assertEquals("reb-members", ((ApprovalRequirement) requirement).getApproverGroup());
    }

    @Test
    void hasNoConditionWhenNotSet()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req", Map.of(
            "sling:resourceType", ApprovalRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", Requirement.RESOURCE_TYPE));
        final Requirement requirement = resource.adaptTo(Requirement.class);

        assertNull(requirement.getCondition());
    }

    @Test
    void exposesSingleConditionAsCondition()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req", Map.of(
            "sling:resourceType", ApprovalRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", Requirement.RESOURCE_TYPE));
        this.context.create().resource("/Schemas/schema/1.0/req/cond:condition", Map.of(
            "sling:resourceType", SingleCondition.RESOURCE_TYPE, "comparator", "equals"));
        final Requirement requirement = resource.adaptTo(Requirement.class);

        final Condition condition = requirement.getCondition();

        assertEquals(SingleCondition.class, condition.getClass());
        assertEquals("equals", ((SingleCondition) condition).getComparator());
    }

    @Test
    void exposesConditionGroupAsCondition()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req", Map.of(
            "sling:resourceType", ApprovalRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", Requirement.RESOURCE_TYPE));
        this.context.create().resource("/Schemas/schema/1.0/req/cond:condition", Map.of(
            "sling:resourceType", ConditionGroup.RESOURCE_TYPE, "requireAll", true));
        final Requirement requirement = resource.adaptTo(Requirement.class);

        final Condition condition = requirement.getCondition();

        assertEquals(ConditionGroup.class, condition.getClass());
        assertTrue(((ConditionGroup) condition).isRequireAll());
    }
}
