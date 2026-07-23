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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FormItem}, exercised through the {@link Question} concrete subtype (an abstract
 * node type has no direct instances of its own). Every subtype, including {@link Section}, inherits this behavior.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FormItemTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, SingleCondition.class,
            ConditionGroup.class, Question.class);
    }

    @Test
    void hasNoConditionWhenNotSet()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/form/q1",
            "sling:resourceType", Question.RESOURCE_TYPE);
        final Question question = resource.adaptTo(Question.class);

        assertNull(question.getCondition());
    }

    @Test
    void exposesSingleConditionAsCondition()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/form/q1",
            "sling:resourceType", Question.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/form/q1/cond:condition", Map.of(
            "sling:resourceType", SingleCondition.RESOURCE_TYPE, "comparator", "equals"));
        final Question question = resource.adaptTo(Question.class);

        final Condition condition = question.getCondition();

        assertEquals(SingleCondition.class, condition.getClass());
        assertEquals("equals", ((SingleCondition) condition).getComparator());
    }

    @Test
    void exposesConditionGroupAsCondition()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/form/q1",
            "sling:resourceType", Question.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/form/q1/cond:condition", Map.of(
            "sling:resourceType", ConditionGroup.RESOURCE_TYPE, "requireAll", true));
        final Question question = resource.adaptTo(Question.class);

        final Condition condition = question.getCondition();

        assertEquals(ConditionGroup.class, condition.getClass());
        assertTrue(((ConditionGroup) condition).isRequireAll());
    }
}
