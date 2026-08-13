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

/**
 * Unit tests for {@link AnswerOption}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerOptionTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, AnswerOption.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/q/option",
            "sling:resourceType", AnswerOption.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(AnswerOption.class));
    }

    @Test
    void exposesTheStoredValueAndTheLabelRead()
    {
        final AnswerOption option = this.option(Map.of(
            "sling:resourceType", AnswerOption.RESOURCE_TYPE,
            "value", "multiple-days",
            "label", "Several days"));

        assertEquals("multiple-days", option.getValue());
        assertEquals("Several days", option.getLabel());
    }

    // An option whose label would only repeat its value may declare the value alone
    @Test
    void readsAsItsValueWhenNoLabelIsGiven()
    {
        assertEquals("sick", this.option(Map.of(
            "sling:resourceType", AnswerOption.RESOURCE_TYPE,
            "value", "sick")).getLabel());
    }

    @Test
    void treatsAnEmptyLabelAsNoLabel()
    {
        assertEquals("sick", this.option(Map.of(
            "sling:resourceType", AnswerOption.RESOURCE_TYPE,
            "value", "sick",
            "label", "")).getLabel());
    }

    private AnswerOption option(final Map<String, Object> properties)
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/q/option", properties);
        return resource.adaptTo(AnswerOption.class);
    }
}
