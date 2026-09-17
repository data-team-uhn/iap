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
package io.uhndata.iap.extraction.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.schemas.models.Question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExtractionField}: which questions are worth putting to a model, and what it is told
 * about them.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ExtractionFieldTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    private int created;

    private Question question(final Map<String, Object> properties)
    {
        this.created++;
        final Map<String, Object> all = new HashMap<>(properties);
        all.put("sling:resourceType", Question.RESOURCE_TYPE);
        final Resource resource = this.context.create()
            .resource("/Schemas/s/1.0/q" + this.created, all);
        final Question model = resource.adaptTo(Question.class);
        assertNotNull(model);
        return model;
    }

    @Test
    void isWorthAskingWhenTheSchemaSaysHow()
    {
        assertTrue(ExtractionField.isExtractable(
            question(Map.of("extractionPrompt", "Find the primary aims."))));
    }

    @Test
    void isNotWorthAskingWithoutAPrompt()
    {
        assertFalse(ExtractionField.isExtractable(question(Map.of("text", "Your name?"))),
            "a question meant for the submitter is not an extraction field");
        assertFalse(ExtractionField.isExtractable(question(Map.of("extractionPrompt", "   "))));
    }

    @Test
    void carriesWhatTheModelNeedsToBeTold()
    {
        final Question question = question(Map.of(
            "text", "What are the primary aims?",
            "purpose", "Whether the study has a stated objective",
            "extractionPrompt", "Find the primary aims.",
            "responseShape", "{\"type\": \"string\"}",
            "rubricTags", new String[]{ "B.3", "B.4" },
            "maxAnswers", 5L));

        final ExtractionField field = ExtractionField.of(question);

        assertEquals("q1", field.name());
        assertEquals("What are the primary aims?", field.text());
        assertEquals("Whether the study has a stated objective", field.purpose());
        assertEquals("Find the primary aims.", field.prompt());
        assertEquals("{\"type\": \"string\"}", field.responseShape());
        assertEquals(List.of("B.3", "B.4"), field.rubricTags());
        assertTrue(field.multiple());
    }

    @Test
    void copesWithAQuestionThatSaysLittle()
    {
        final ExtractionField field =
            ExtractionField.of(question(Map.of("extractionPrompt", "Find the aims.")));

        assertNull(field.responseShape(), "a field with no shape takes whatever the model says");
        assertEquals(List.of(), field.rubricTags(), "no tags means the whole document");
        assertFalse(field.multiple());
    }
}
