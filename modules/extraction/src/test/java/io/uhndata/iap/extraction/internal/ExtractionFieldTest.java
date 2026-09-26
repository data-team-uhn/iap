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

import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.Question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void readsAQuestionIntoAFieldToPutToTheModel()
    {
        final Question question = question(Map.of(
            "text", "What are the aims?",
            "purpose", "Whether there is one",
            "extractionPrompt", "Find the primary aims.",
            "responseShape", "{\"type\":\"string\"}",
            "maxAnswers", 0L));

        final ExtractionField field = ExtractionField.of(question, question.getName());

        assertEquals(question.getName(), field.name(), "the node name is what the answer is filed under");
        assertEquals("What are the aims?", field.text());
        assertEquals("Whether there is one", field.purpose());
        assertEquals("Find the primary aims.", field.prompt());
        assertEquals("{\"type\":\"string\"}", field.responseShape());
        assertTrue(field.multiple());
    }

    // A question that offers options is answered with their values, and the model has to be told them: left to
    // its own words it names "English", which ticks nothing
    @Test
    void tellsTheModelTheValuesAnOptionQuestionTakes()
    {
        final Question many = question(Map.of("extractionPrompt", "Name the languages.", "maxAnswers", 0L));
        option(many, "english", "English");
        option(many, "french", "French");
        final Question one = question(Map.of("extractionPrompt", "Say which kind.", "maxAnswers", 1L));
        option(one, "prom", "PROM");

        assertEquals("Name the languages. Answer with one or more of these values, separated by commas: "
            + "english (English), french (French).", ExtractionField.of(many, "languages").prompt());
        assertEquals("Say which kind. Answer with exactly one of these values: prom (PROM).",
            ExtractionField.of(one, "kind").prompt());
    }

    // A description goes with the label, so a category the model is choosing among can say what belongs there
    @Test
    void tellsTheModelTheDescriptionAnOptionCarries()
    {
        final Question kind = question(Map.of("extractionPrompt", "Say which kind."));
        this.context.create().resource(kind.getPath() + "/prom", Map.of(
            "sling:resourceType", AnswerOption.RESOURCE_TYPE, "value", "prom", "label", "PROM",
            "description", "The patient's own health."));

        assertEquals("Say which kind. Answer with exactly one of these values: prom (PROM) -- "
            + "The patient's own health.", ExtractionField.of(kind, "kind").prompt());
        assertEquals(List.of("prom"), ExtractionField.of(kind, "kind").allowedValues());
    }

    private void option(final Question question, final String value, final String label)
    {
        this.context.create().resource(question.getPath() + "/" + value, Map.of(
            "sling:resourceType", AnswerOption.RESOURCE_TYPE, "value", value, "label", label));
    }
}
