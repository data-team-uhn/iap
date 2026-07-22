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

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConditionOperand}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ConditionOperandTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionOperand.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/cond/operandA",
            "sling:resourceType", ConditionOperand.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(ConditionOperand.class));
    }

    @Test
    void exposesLiteralValue()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/cond/operandA",
            Map.of(
                "sling:resourceType", ConditionOperand.RESOURCE_TYPE,
                "value", new String[]{ "yes" },
                "isReference", false));
        final ConditionOperand value = resource.adaptTo(ConditionOperand.class);

        assertArrayEquals(new String[]{ "yes" }, value.getValue());
        assertFalse(value.isReference());
    }

    @Test
    void exposesReferenceValue()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/cond/operandB",
            Map.of(
                "sling:resourceType", ConditionOperand.RESOURCE_TYPE,
                "value", new String[]{ "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345" },
                "isReference", true));
        final ConditionOperand value = resource.adaptTo(ConditionOperand.class);

        assertArrayEquals(new String[]{ "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345" }, value.getValue());
        assertTrue(value.isReference());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/cond/bare",
            "sling:resourceType", ConditionOperand.RESOURCE_TYPE);
        final ConditionOperand value = resource.adaptTo(ConditionOperand.class);

        assertNotNull(value);
        assertNull(value.getValue());
        assertFalse(value.isReference());
    }
}
