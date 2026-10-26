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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.Question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExtractionField}: what of a schema question reaches the model.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ExtractionFieldTest
{
    private static final String VERSION = "/Schemas/proposal/v1";

    private static Question question(final String extractionPrompt)
    {
        final Question question = Mockito.mock(Question.class);
        Mockito.when(question.getPath()).thenReturn(VERSION + "/common/title");
        Mockito.when(question.getText()).thenReturn("What is the title of the study?");
        Mockito.when(question.getDataType()).thenReturn("text");
        Mockito.when(question.getExtractionPrompt()).thenReturn(extractionPrompt);
        return question;
    }

    private static AnswerOption option(final String value, final String label, final String description)
    {
        final AnswerOption option = Mockito.mock(AnswerOption.class);
        Mockito.when(option.getValue()).thenReturn(value);
        Mockito.when(option.getLabel()).thenReturn(label);
        Mockito.when(option.getDescription()).thenReturn(description);
        return option;
    }

    @Test
    void asksOnlyAQuestionWithInstructions()
    {
        assertTrue(ExtractionField.isExtractable(question("Find the title on the cover page.")));
        assertFalse(ExtractionField.isExtractable(question("  ")));
        assertFalse(ExtractionField.isExtractable(question(null)));
    }

    @Test
    void keysAQuestionByItsPathInsideTheSchemaVersion()
    {
        assertEquals("common.title", ExtractionField.keyFor(VERSION, question("Find it.")));
    }

    @Test
    void readsEverythingAboutTheAnswerOffTheQuestion()
    {
        final Question question = question("  Find the planned number of participants:\n- across all sites  ");
        Mockito.when(question.getDataType()).thenReturn(ExtractionField.LONG);
        Mockito.when(question.getDescription()).thenReturn("Across all sites");
        Mockito.when(question.getPurpose()).thenReturn("Whether the size matches the aims");
        Mockito.when(question.isMultiple()).thenReturn(true);
        Mockito.when(question.getMinValue()).thenReturn(1.0);
        Mockito.when(question.getMaxValue()).thenReturn(5000.0);

        final ExtractionField field = ExtractionField.of("common.size", question);

        assertEquals("common.size", field.key());
        assertEquals("What is the title of the study?", field.text());
        assertEquals("Across all sites", field.description());
        assertEquals("Whether the size matches the aims", field.purpose());
        // The instructions keep their lines, which may lay out a list
        assertEquals("Find the planned number of participants:\n- across all sites", field.instructions());
        assertEquals(ExtractionField.LONG, field.answer().dataType());
        assertTrue(field.answer().multiple());
        assertEquals(1.0, field.answer().minValue());
        assertEquals(5000.0, field.answer().maxValue());
        assertTrue(field.answer().options().isEmpty());
    }

    @Test
    void leavesOutWhatTheQuestionDoesNotSay()
    {
        final Question question = question("Find it.");
        Mockito.when(question.getDescription()).thenReturn(" ");

        final ExtractionField field = ExtractionField.of("common.title", question);

        assertNull(field.description());
        assertNull(field.purpose());
        assertNull(field.answer().format());
    }

    @Test
    void takesAQuestionNamingNoDataTypeForText()
    {
        final Question question = question("Find it.");
        Mockito.when(question.getDataType()).thenReturn(null);

        assertEquals(ExtractionField.TEXT, ExtractionField.of("k", question).answer().dataType());
    }

    @Test
    void prefersThePatternInWordsToTheExpression()
    {
        final Question question = question("Find it.");
        Mockito.when(question.getPattern()).thenReturn("^[A-Z]{3}-\\d{4}$");
        assertEquals("^[A-Z]{3}-\\d{4}$", ExtractionField.of("k", question).answer().format());

        Mockito.when(question.getPatternMessage()).thenReturn("Three letters, a dash, four digits");
        assertEquals("Three letters, a dash, four digits", ExtractionField.of("k", question).answer().format());
    }

    @Test
    void offersTheQuestionsOptionsInOrder()
    {
        final Question question = question("Decide which.");
        final List<AnswerOption> options = List.of(option("drug", "Drug", "Including biologics\n and vaccines"),
            option("device", null, null));
        Mockito.when(question.getOptions()).thenReturn(options);

        final List<ExtractionField.Option> offered = ExtractionField.of("k", question).answer().options();

        assertEquals(List.of(new ExtractionField.Option("drug", "Drug", "Including biologics and vaccines"),
            // An option with no label is shown under its value
            new ExtractionField.Option("device", "device", null)), offered);
    }
}
