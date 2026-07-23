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

import java.util.Calendar;
import java.util.Map;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PropertyOperandResolver}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class PropertyOperandResolverTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String ENTITY_TYPE = "iap/Entity";

    private final SlingContext context = new SlingContext();

    private final PropertyOperandResolver resolver = new PropertyOperandResolver();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionOperand.class);
    }

    private ConditionOperand createOperand(final String... propertyName)
    {
        return this.context.create().resource("/Entities/operandA", Map.of(
            SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE,
            "source", "property",
            "value", propertyName)).adaptTo(ConditionOperand.class);
    }

    @Test
    void implementsThePropertySource()
    {
        assertEquals("property", this.resolver.getSource());
    }

    @Test
    void resolvesToTheEnclosingEntityProperty()
    {
        final ConditionOperand operand = this.createOperand("status");
        this.context.create().resource("/Submissions/sub", Map.of(
            SLING_RESOURCE_TYPE, ENTITY_TYPE,
            "status", "approved"));
        // The condition is evaluated somewhere inside the entity, its metadata still applies
        final Resource part = this.context.create().resource("/Submissions/sub/part");

        final Operand resolved = this.resolver.resolve(operand, part);

        assertEquals(1, resolved.size());
        assertEquals("approved", resolved.get(0));
    }

    @Test
    void exposesTheStoredPropertyType()
    {
        final ConditionOperand operand = this.createOperand("jcr:created");
        final Calendar created = Calendar.getInstance();
        final Resource submission = this.context.create().resource("/Submissions/sub", Map.of(
            SLING_RESOURCE_TYPE, ENTITY_TYPE,
            "jcr:created", created));

        final Operand resolved = this.resolver.resolve(operand, submission);

        assertEquals(created, resolved.get(0));
        assertEquals(OperandType.DATE, resolved.getEffectiveType());
    }

    @Test
    void resolvesToEmptyWhenThePropertyIsNotSet()
    {
        final ConditionOperand operand = this.createOperand("status");
        final Resource submission = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
    }

    @Test
    void resolvesToEmptyWhenNoPropertyIsNamed()
    {
        final ConditionOperand operand = this.createOperand();
        final Resource submission = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);

        assertTrue(this.resolver.resolve(operand, submission).isEmpty());
    }

    @Test
    void fallsBackToTheContextItselfOutsideAnyEntity()
    {
        final ConditionOperand operand = this.createOperand("status");
        final Resource untyped = this.context.create().resource("/orphan", Map.of("status", "loose"));

        final Operand resolved = this.resolver.resolve(operand, untyped);

        assertEquals("loose", resolved.get(0));
    }
}
