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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link QuestionnaireItem}, exercised through the {@link Question} concrete subtype (an abstract
 * node type has no direct instances of its own). Every subtype, including {@link Section}, inherits this behavior.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class QuestionnaireItemTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, SingleCondition.class,
            ConditionGroup.class, Question.class);
    }

    @Test
    void listsNoConditionsWhenNoneExist()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/questionnaire/q1",
            "sling:resourceType", Question.RESOURCE_TYPE);
        final Question question = resource.adaptTo(Question.class);

        assertTrue(question.getConditions().isEmpty());
    }

    @Test
    void listsConditionsUsingTheSpecificModelForEach()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/questionnaire/q1",
            "sling:resourceType", Question.RESOURCE_TYPE);
        // sling:resourceSuperType is mandatory/autocreated on sch:Condition in the real CND; sling-mock
        // doesn't know about the CND, so it must be set explicitly here.
        this.context.create().resource("/Schemas/schema/1.0/questionnaire/q1/c1", Map.of(
            "sling:resourceType", SingleCondition.RESOURCE_TYPE, "sling:resourceSuperType", Condition.RESOURCE_TYPE,
            "comparator", "equals"));
        this.context.create().resource("/Schemas/schema/1.0/questionnaire/q1/g1", Map.of(
            "sling:resourceType", ConditionGroup.RESOURCE_TYPE, "sling:resourceSuperType", Condition.RESOURCE_TYPE,
            "requireAll", true));
        final Question question = resource.adaptTo(Question.class);

        final List<Condition> conditions = question.getConditions();

        assertEquals(2, conditions.size());
        assertEquals(SingleCondition.class, conditions.get(0).getClass());
        assertEquals("equals", ((SingleCondition) conditions.get(0)).getComparator());
        assertEquals(ConditionGroup.class, conditions.get(1).getClass());
        assertTrue(((ConditionGroup) conditions.get(1)).isRequireAll());
    }
}
