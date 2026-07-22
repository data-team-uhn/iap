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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConditionGroup}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ConditionGroupTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionOperand.class,
            SingleCondition.class, ConditionGroup.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/group",
            "sling:resourceType", ConditionGroup.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(ConditionGroup.class));
    }

    @Test
    void exposesRequireAll()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/group", Map.of(
            "sling:resourceType", ConditionGroup.RESOURCE_TYPE,
            "requireAll", true));
        assertTrue(resource.adaptTo(ConditionGroup.class).isRequireAll());
    }

    @Test
    void defaultsToNotRequireAll()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/group",
            "sling:resourceType", ConditionGroup.RESOURCE_TYPE);
        assertFalse(resource.adaptTo(ConditionGroup.class).isRequireAll());
    }

    @Test
    void listsSingleConditionsAndNestedGroupsSeparately()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/group",
            "sling:resourceType", ConditionGroup.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/req/group/c1",
            "sling:resourceType", SingleCondition.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/req/group/g1",
            "sling:resourceType", ConditionGroup.RESOURCE_TYPE);
        final ConditionGroup group = resource.adaptTo(ConditionGroup.class);

        final List<SingleCondition> singleConditions = group.getSingleConditions();
        assertEquals(1, singleConditions.size());
        assertEquals("c1", singleConditions.get(0).getName());

        final List<ConditionGroup> groups = group.getConditionGroups();
        assertEquals(1, groups.size());
        assertEquals("g1", groups.get(0).getName());
    }

    @Test
    void listsConditionsUsingTheSpecificModelForEach()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/group",
            "sling:resourceType", ConditionGroup.RESOURCE_TYPE);
        // sling:resourceSuperType is mandatory/autocreated on sch:Condition in the real CND; sling-mock
        // doesn't know about the CND, so it must be set explicitly here.
        this.context.create().resource("/Schemas/schema/1.0/req/group/c1", Map.of(
            "sling:resourceType", SingleCondition.RESOURCE_TYPE, "sling:resourceSuperType", Condition.RESOURCE_TYPE,
            "comparator", "equals"));
        this.context.create().resource("/Schemas/schema/1.0/req/group/g1", Map.of(
            "sling:resourceType", ConditionGroup.RESOURCE_TYPE, "sling:resourceSuperType", Condition.RESOURCE_TYPE,
            "requireAll", true));
        final ConditionGroup group = resource.adaptTo(ConditionGroup.class);

        final List<Condition> conditions = group.getConditions();

        assertEquals(2, conditions.size());
        assertEquals(SingleCondition.class, conditions.get(0).getClass());
        assertEquals("equals", ((SingleCondition) conditions.get(0)).getComparator());
        assertEquals(ConditionGroup.class, conditions.get(1).getClass());
        assertTrue(((ConditionGroup) conditions.get(1)).isRequireAll());
    }
}
