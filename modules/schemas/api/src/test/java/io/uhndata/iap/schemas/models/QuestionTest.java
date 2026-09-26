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

import java.util.HashMap;
import java.util.List;
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
        this.context.addModelsForClasses(Content.class, EntityPart.class, Question.class, AnswerOption.class);
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
            "minAnswers", 1L,
            "maxAnswers", 1L));
        final Question question = resource.adaptTo(Question.class);

        assertEquals("Does this study involve human subjects?", question.getText());
        assertEquals("Select the option that best describes your study", question.getDescription());
        assertEquals("boolean", question.getDataType());
        assertEquals(1, question.getMinAnswers());
        assertEquals(1, question.getMaxAnswers());
        assertTrue(question.isRequired());
        assertFalse(question.isMultiple());
    }

    @Test
    void derivesRequiredAndMultipleFromTheCounts()
    {
        final Question range = this.question("keywords", Map.of("minAnswers", 2L, "maxAnswers", 4L));
        assertTrue(range.isRequired());
        assertTrue(range.isMultiple());

        final Question unbounded = this.question("notes", Map.of("minAnswers", 0L, "maxAnswers", -1L));
        assertFalse(unbounded.isRequired());
        assertTrue(unbounded.isMultiple());
    }

    @Test
    void exposesTheValueConstraints()
    {
        final Question question = this.question("participants", Map.of(
            "dataType", "long",
            "minValue", 1.0d,
            "maxValue", 500.0d,
            "pattern", "[0-9]+",
            "patternMessage", "Enter a whole number."));

        assertEquals(1.0d, question.getMinValue());
        assertEquals(500.0d, question.getMaxValue());
        assertEquals("[0-9]+", question.getPattern());
        assertEquals("Enter a whole number.", question.getPatternMessage());
    }

    @Test
    void offersItsOptionsInTheOrderTheyAreDeclared()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/duration",
            "sling:resourceType", Question.RESOURCE_TYPE);
        this.option(resource, "half", "half-day", "Half day");
        this.option(resource, "full", "full-day", "Full day");
        this.option(resource, "several", "multiple-days", "Several days");
        this.context.create().resource(resource.getPath() + "/cond:condition",
            "sling:resourceType", "cond/SingleCondition");

        final List<AnswerOption> options = resource.adaptTo(Question.class).getOptions();

        assertEquals(List.of("half-day", "full-day", "multiple-days"),
            options.stream().map(AnswerOption::getValue).toList());
        assertEquals(List.of("Half day", "Full day", "Several days"),
            options.stream().map(AnswerOption::getLabel).toList());
    }

    @Test
    void exposesThePathItsOptionsComeFrom()
    {
        assertEquals("/Categories",
            this.question("category", Map.of("optionsFrom", "/Categories")).getOptionsFrom());
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
        assertEquals(0, question.getMinAnswers());
        assertEquals(1, question.getMaxAnswers());
        assertFalse(question.isRequired());
        assertFalse(question.isMultiple());
        assertNull(question.getMinValue());
        assertNull(question.getMaxValue());
        assertNull(question.getPattern());
        assertNull(question.getPatternMessage());
        assertNull(question.getOptionsFrom());
        assertTrue(question.getOptions().isEmpty());
        assertNull(question.getDisplayMode());
        assertNull(question.getPurpose());
        assertNull(question.getExtractionPrompt());
        assertNull(question.getResponseShape());
        assertTrue(question.getRubricTags().isEmpty());
    }

    @Test
    void exposesWhatAnswerExtractionNeeds()
    {
        final Resource resource = this.context.create().resource("/Schemas/schema/1.0/participants", Map.of(
            "sling:resourceType", Question.RESOURCE_TYPE,
            "text", "How many participants will be recruited?",
            "displayMode", "number",
            "purpose", "Whether the sample size matches the statistical plan",
            "extractionPrompt", "Read the target number of participants out of the recruitment section",
            "responseShape", "{\"type\": \"integer\"}",
            "rubricTags", new String[]{ "recruitment", "statistics" }));
        final Question question = resource.adaptTo(Question.class);

        assertEquals("number", question.getDisplayMode());
        assertEquals("Whether the sample size matches the statistical plan", question.getPurpose());
        assertEquals("Read the target number of participants out of the recruitment section",
            question.getExtractionPrompt());
        assertEquals("{\"type\": \"integer\"}", question.getResponseShape());
        assertEquals(List.of("recruitment", "statistics"), question.getRubricTags());
    }

    private Question question(final String name, final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put("sling:resourceType", Question.RESOURCE_TYPE);
        return this.context.create().resource("/Schemas/schema/1.0/" + name, all).adaptTo(Question.class);
    }

    private void option(final Resource question, final String name, final String value, final String label)
    {
        this.context.create().resource(question.getPath() + "/" + name, Map.of(
            "sling:resourceType", AnswerOption.RESOURCE_TYPE,
            "value", value,
            "label", label));
    }
}
