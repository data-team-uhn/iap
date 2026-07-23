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
package io.uhndata.iap.conditions.models;

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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link SingleCondition}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SingleConditionTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionOperand.class,
            SingleCondition.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Entities/entity/part/cond",
            "sling:resourceType", SingleCondition.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(SingleCondition.class));
    }

    @Test
    void exposesConditionProperties()
    {
        final Resource resource = this.context.create().resource("/Entities/entity/part/cond", Map.of(
            "sling:resourceType", SingleCondition.RESOURCE_TYPE,
            "comparator", "equals"));
        final SingleCondition condition = resource.adaptTo(SingleCondition.class);

        assertEquals("equals", condition.getComparator());
    }

    @Test
    void exposesOperands()
    {
        final Resource resource = this.context.create().resource("/Entities/entity/part/cond",
            "sling:resourceType", SingleCondition.RESOURCE_TYPE);
        this.context.create().resource("/Entities/entity/part/cond/operandA",
            "sling:resourceType", ConditionOperand.RESOURCE_TYPE, "value", new String[]{ "a" });
        this.context.create().resource("/Entities/entity/part/cond/operandB",
            "sling:resourceType", ConditionOperand.RESOURCE_TYPE, "value", new String[]{ "b" });
        final SingleCondition condition = resource.adaptTo(SingleCondition.class);

        assertNotNull(condition.getOperandA());
        assertEquals("a", condition.getOperandA().getValue()[0]);
        assertNotNull(condition.getOperandB());
        assertEquals("b", condition.getOperandB().getValue()[0]);
    }

    @Test
    void toleratesMissingOperandB()
    {
        final Resource resource = this.context.create().resource("/Entities/entity/part/cond",
            "sling:resourceType", SingleCondition.RESOURCE_TYPE);
        this.context.create().resource("/Entities/entity/part/cond/operandA",
            "sling:resourceType", ConditionOperand.RESOURCE_TYPE);
        final SingleCondition condition = resource.adaptTo(SingleCondition.class);

        assertNotNull(condition.getOperandA());
        assertNull(condition.getOperandB());
    }
}
