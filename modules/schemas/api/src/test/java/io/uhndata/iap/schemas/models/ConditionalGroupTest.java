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
 * Unit tests for {@link ConditionalGroup}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ConditionalGroupTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionalValue.class, Conditional.class,
            ConditionalGroup.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/group",
            "sling:resourceType", ConditionalGroup.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(ConditionalGroup.class));
    }

    @Test
    void exposesRequireAll()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/group", Map.of(
            "sling:resourceType", ConditionalGroup.RESOURCE_TYPE,
            "requireAll", true));
        assertTrue(resource.adaptTo(ConditionalGroup.class).isRequireAll());
    }

    @Test
    void defaultsToNotRequireAll()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/group",
            "sling:resourceType", ConditionalGroup.RESOURCE_TYPE);
        assertFalse(resource.adaptTo(ConditionalGroup.class).isRequireAll());
    }

    @Test
    void listsConditionalsAndNestedGroupsSeparately()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/group",
            "sling:resourceType", ConditionalGroup.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/req/group/c1",
            "sling:resourceType", Conditional.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/req/group/g1",
            "sling:resourceType", ConditionalGroup.RESOURCE_TYPE);
        final ConditionalGroup group = resource.adaptTo(ConditionalGroup.class);

        final List<Conditional> conditionals = group.getConditionals();
        assertEquals(1, conditionals.size());
        assertEquals("c1", conditionals.get(0).getName());

        final List<ConditionalGroup> groups = group.getConditionalGroups();
        assertEquals(1, groups.size());
        assertEquals("g1", groups.get(0).getName());
    }

    @Test
    void listsConditionsUsingTheSpecificModelForEach()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/group",
            "sling:resourceType", ConditionalGroup.RESOURCE_TYPE);
        // sling:resourceSuperType is mandatory/autocreated on sch:Condition in the real CND; sling-mock
        // doesn't know about the CND, so it must be set explicitly here.
        this.context.create().resource("/Schemas/schema/1.0/req/group/c1", Map.of(
            "sling:resourceType", Conditional.RESOURCE_TYPE, "sling:resourceSuperType", Condition.RESOURCE_TYPE,
            "comparator", "equals"));
        this.context.create().resource("/Schemas/schema/1.0/req/group/g1", Map.of(
            "sling:resourceType", ConditionalGroup.RESOURCE_TYPE, "sling:resourceSuperType", Condition.RESOURCE_TYPE,
            "requireAll", true));
        final ConditionalGroup group = resource.adaptTo(ConditionalGroup.class);

        final List<Condition> conditions = group.getConditions();

        assertEquals(2, conditions.size());
        assertEquals(Conditional.class, conditions.get(0).getClass());
        assertEquals("equals", ((Conditional) conditions.get(0)).getComparator());
        assertEquals(ConditionalGroup.class, conditions.get(1).getClass());
        assertTrue(((ConditionalGroup) conditions.get(1)).isRequireAll());
    }
}
