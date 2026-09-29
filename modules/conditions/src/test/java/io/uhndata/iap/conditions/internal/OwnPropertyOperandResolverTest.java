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

import java.util.Map;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OwnPropertyOperandResolver}: the content's own property, not its entity's.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class OwnPropertyOperandResolverTest
{
    private final SlingContext context = new SlingContext();

    private final OwnPropertyOperandResolver resolver = new OwnPropertyOperandResolver();

    private int operands;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionOperand.class);
    }

    private ConditionOperand operand(final String... names)
    {
        return this.context.create().resource("/Entities/operand" + this.operands++, Map.of(
            "sling:resourceType", ConditionOperand.RESOURCE_TYPE, "source", "ownProperty", "value", names))
            .adaptTo(ConditionOperand.class);
    }

    @Test
    void implementsTheOwnPropertySource()
    {
        assertEquals("ownProperty", this.resolver.getSource());
    }

    @Test
    void readsTheContentItselfRatherThanItsEntity()
    {
        this.context.create().resource("/Schemas/version", Map.of(
            "sling:resourceType", "data/Entity", "optionsFrom", "/Elsewhere"));
        final Content question = this.context.create().resource("/Schemas/version/question",
            Map.of("text", "Where?")).adaptTo(Content.class);

        assertTrue(this.resolver.resolve(operand("optionsFrom"), question).isEmpty());
        final Operand text = this.resolver.resolve(operand("text"), question);
        assertEquals(1, text.size());
        assertEquals("Where?", text.get(0));
    }

    @Test
    void resolvesToNothingWhenItNamesNoProperty()
    {
        final Content content = this.context.create().resource("/Content/item").adaptTo(Content.class);

        assertTrue(this.resolver.resolve(operand(), content).isEmpty());
    }
}
