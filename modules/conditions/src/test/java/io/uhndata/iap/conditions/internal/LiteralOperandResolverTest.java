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
package io.uhndata.iap.conditions.internal;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LiteralOperandResolver}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LiteralOperandResolverTest
{
    private final SlingContext context = new SlingContext();

    private final LiteralOperandResolver resolver = new LiteralOperandResolver();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionOperand.class);
    }

    @Test
    void implementsTheLiteralSource()
    {
        assertEquals("literal", this.resolver.getSource());
    }

    @Test
    void resolvesToTheStoredValues()
    {
        final Resource resource = this.context.create().resource("/Entities/entity/cond/operandA",
            "sling:resourceType", ConditionOperand.RESOURCE_TYPE, "value", new String[]{ "18", "21" });
        final ConditionOperand operand = resource.adaptTo(ConditionOperand.class);

        final Operand resolved = this.resolver.resolve(operand, resource);

        assertEquals(2, resolved.size());
        assertEquals("18", resolved.get(0));
        assertEquals("21", resolved.get(1));
        // Plain strings declare nothing, they follow the other side of the comparison
        assertNull(resolved.getEffectiveType());
    }

    @Test
    void preservesStoredValueTypes()
    {
        final Resource resource = this.context.create().resource("/Entities/entity/cond/operandA",
            "sling:resourceType", ConditionOperand.RESOURCE_TYPE, "value", new Long[]{ 18L, 21L });
        final ConditionOperand operand = resource.adaptTo(ConditionOperand.class);

        final Operand resolved = this.resolver.resolve(operand, resource);

        assertEquals(18L, resolved.get(0));
        assertEquals(OperandType.LONG, resolved.getEffectiveType());
    }

    @Test
    void resolvesToEmptyWhenNoValuesAreStored()
    {
        final Resource resource = this.context.create().resource("/Entities/entity/cond/operandA",
            "sling:resourceType", ConditionOperand.RESOURCE_TYPE);
        final ConditionOperand operand = resource.adaptTo(ConditionOperand.class);

        assertTrue(this.resolver.resolve(operand, resource).isEmpty());
    }
}
