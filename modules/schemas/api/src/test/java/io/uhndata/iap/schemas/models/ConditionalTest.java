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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link Conditional}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ConditionalTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionalValue.class, Conditional.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/cond",
            "sling:resourceType", Conditional.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Conditional.class));
    }

    @Test
    void exposesConditionalProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/cond", Map.of(
            "sling:resourceType", Conditional.RESOURCE_TYPE,
            "comparator", "equals",
            "dataType", "boolean"));
        final Conditional conditional = resource.adaptTo(Conditional.class);

        assertEquals("equals", conditional.getComparator());
        assertEquals("boolean", conditional.getDataType());
    }

    @Test
    void exposesOperands()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/cond",
            "sling:resourceType", Conditional.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/req/cond/operandA",
            "sling:resourceType", ConditionalValue.RESOURCE_TYPE, "value", new String[]{ "a" });
        this.context.create().resource("/Schemas/schema/1.0/req/cond/operandB",
            "sling:resourceType", ConditionalValue.RESOURCE_TYPE, "value", new String[]{ "b" });
        final Conditional conditional = resource.adaptTo(Conditional.class);

        assertNotNull(conditional.getOperandA());
        assertEquals("a", conditional.getOperandA().getValue()[0]);
        assertNotNull(conditional.getOperandB());
        assertEquals("b", conditional.getOperandB().getValue()[0]);
    }

    @Test
    void toleratesMissingOperandB()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/req/cond",
            "sling:resourceType", Conditional.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/req/cond/operandA",
            "sling:resourceType", ConditionalValue.RESOURCE_TYPE);
        final Conditional conditional = resource.adaptTo(Conditional.class);

        assertNotNull(conditional.getOperandA());
        assertNull(conditional.getOperandB());
    }
}
