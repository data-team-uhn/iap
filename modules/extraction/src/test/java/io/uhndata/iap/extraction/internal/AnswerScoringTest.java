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

import java.io.StringReader;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AnswerScoring}, since the evaluation's numbers are only as good as its comparisons.
 *
 * @version $Id$
 * @since 0.1.0
 */
class AnswerScoringTest
{
    private static final List<ExtractionField.Option> KINDS = List.of(new ExtractionField.Option("drug", "Drug", null),
        new ExtractionField.Option("device", "Device", null), new ExtractionField.Option("other", "Other", null));

    private static ExtractionField field(final String dataType, final boolean multiple,
        final List<ExtractionField.Option> options)
    {
        return new ExtractionField("k", "Q?", null, null, "Find it.",
            new ExtractionField.Answer(dataType, multiple, null, null, null, options));
    }

    private static JsonValue json(final String json)
    {
        return Json.createReader(new StringReader("[" + json + "]")).readArray().get(0);
    }

    private static AnswerScoring.Score score(final ExtractionField field, final List<String> values,
        final String expected)
    {
        return AnswerScoring.score(field, values, expected == null ? null : json(expected));
    }

    @Test
    void scoresNothingWithoutAnExpectedAnswer()
    {
        assertFalse(score(field("text", false, List.of()), List.of("x"), null).scored());
        assertFalse(score(field("text", false, List.of()), List.of("x"), "null").scored());
    }

    @Test
    void countsAMissingAnswerAsWrong()
    {
        final AnswerScoring.Score score = score(field("text", false, List.of()), List.of(), "\"A title\"");

        assertTrue(score.scored());
        assertFalse(score.correct());
        assertEquals(0.0, score.f1());
    }

    @Test
    void comparesFreeTextWordByWord()
    {
        final ExtractionField title = field("text", false, List.of());

        assertTrue(score(title, List.of("A Trial of Sardines in Prediabetes"),
            "\"Sardine trial: a trial of sardines in prediabetes\"").correct());
        assertFalse(score(title, List.of("Effects of exercise"), "\"A trial of sardines in prediabetes\"").correct());
        assertEquals(0.0, score(title, List.of("..."), "\"A trial\"").f1());
    }

    @Test
    void acceptsAnAlternativeText()
    {
        assertTrue(score(field("text", false, List.of()), List.of("Sardines for prediabetes"),
            "{\"value\": \"The PREDIRCAM sardine-enriched diet trial\","
            + " \"alternatives\": [\"Sardines for Prediabetes\"]}").correct());
    }

    @Test
    void comparesANumberWithinItsTolerance()
    {
        final ExtractionField size = field(ExtractionField.LONG, false, List.of());

        assertTrue(score(size, List.of("182"), "182").correct());
        assertFalse(score(size, List.of("180"), "182").correct());
        assertTrue(score(size, List.of("200"), "{\"value\": 182, \"tolerance\": 0.25}").correct());
        assertFalse(score(size, List.of("about 180"), "182").correct());
        assertFalse(score(size, List.of("182"), "\"182\"").correct());
    }

    @Test
    void comparesAnOptionOrAYesOrNoExactly()
    {
        assertTrue(score(field("text", false, KINDS), List.of("device"), "\"device\"").correct());
        assertFalse(score(field("text", false, KINDS), List.of("drug"), "\"device\"").correct());
        assertTrue(score(field(ExtractionField.BOOLEAN, false, List.of()), List.of("true"), "true").correct());
        assertFalse(score(field(ExtractionField.BOOLEAN, false, List.of()), List.of("false"), "true").correct());
    }

    @Test
    void comparesSeveralOptionsAsSets()
    {
        final ExtractionField kinds = field("text", true, KINDS);

        final AnswerScoring.Score same = score(kinds, List.of("device", "drug"), "[\"drug\", \"device\"]");
        assertTrue(same.correct());
        assertEquals(1.0, same.f1());
        final AnswerScoring.Score partly = score(kinds, List.of("drug"), "[\"drug\", \"device\"]");
        assertFalse(partly.correct());
        assertEquals(2.0 / 3, partly.f1(), 1e-9);
        assertEquals(0.0, score(kinds, List.of("other"), "[\"drug\"]").f1());
    }
}
