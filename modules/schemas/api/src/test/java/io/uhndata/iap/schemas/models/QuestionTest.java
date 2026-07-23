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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Question}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class QuestionTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Question.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/q",
            "sling:resourceType", Question.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Question.class));
    }

    @Test
    void exposesQuestionProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/q", Map.of(
            "sling:resourceType", Question.RESOURCE_TYPE,
            "text", "Does this study involve human subjects?",
            "description", "Select the option that best describes your study",
            "dataType", "boolean",
            "required", true,
            "multiple", false));
        final Question question = resource.adaptTo(Question.class);

        assertEquals("Does this study involve human subjects?", question.getText());
        assertEquals("Select the option that best describes your study", question.getDescription());
        assertEquals("boolean", question.getDataType());
        assertTrue(question.isRequired());
        assertFalse(question.isMultiple());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/bare",
            "sling:resourceType", Question.RESOURCE_TYPE);
        final Question question = resource.adaptTo(Question.class);

        assertNotNull(question);
        assertNull(question.getText());
        assertNull(question.getDescription());
        assertNull(question.getDataType());
        assertFalse(question.isRequired());
        assertFalse(question.isMultiple());
    }
}
