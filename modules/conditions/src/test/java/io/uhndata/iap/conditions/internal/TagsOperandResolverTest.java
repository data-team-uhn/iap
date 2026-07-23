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

import java.util.List;
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
 * Unit tests for {@link TagsOperandResolver}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TagsOperandResolverTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String ENTITY_TYPE = "iap/Entity";

    private final SlingContext context = new SlingContext();

    private final TagsOperandResolver resolver = new TagsOperandResolver();

    private ConditionOperand operand;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionOperand.class);
        this.operand = this.context.create().resource("/Entities/operandA", Map.of(
            SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE,
            "source", "tags")).adaptTo(ConditionOperand.class);
    }

    @Test
    void implementsTheTagsSource()
    {
        assertEquals("tags", this.resolver.getSource());
    }

    @Test
    void resolvesToTheDistinctUnionOfAllTagProperties()
    {
        this.context.create().resource("/Submissions/sub", Map.of(
            SLING_RESOURCE_TYPE, ENTITY_TYPE,
            "tags", new String[]{ "sensitive", "draft" },
            "aggregatedTags", new String[]{ "draft", "incomplete" },
            "inheritedTags", new String[]{ "external" }));
        // The condition is evaluated somewhere inside the entity, its tags still apply
        final Resource part = this.context.create().resource("/Submissions/sub/part");

        final Operand resolved = this.resolver.resolve(this.operand, part);

        assertEquals(List.of("sensitive", "draft", "incomplete", "external"), resolved.stream().toList());
        assertEquals(OperandType.TEXT, resolved.getType());
    }

    @Test
    void resolvesToEmptyWhenNothingIsTagged()
    {
        final Resource submission = this.context.create().resource("/Submissions/sub",
            SLING_RESOURCE_TYPE, ENTITY_TYPE);

        assertTrue(this.resolver.resolve(this.operand, submission).isEmpty());
    }

    @Test
    void fallsBackToTheContextItselfOutsideAnyEntity()
    {
        final Resource untyped = this.context.create().resource("/orphan", Map.of(
            "tags", new String[]{ "loose" }));

        final Operand resolved = this.resolver.resolve(this.operand, untyped);

        assertEquals(List.of("loose"), resolved.stream().toList());
    }
}
