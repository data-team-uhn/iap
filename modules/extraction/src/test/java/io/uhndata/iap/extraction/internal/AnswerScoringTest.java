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
    private static final String KEY = "k";

    private static final List<ExtractionField.Option> KINDS = List.of(new ExtractionField.Option("drug", "Drug", null),
        new ExtractionField.Option("device", "Device", null), new ExtractionField.Option("other", "Other", null));

    private static JsonValue parseJson(final String json)
    {
        return Json.createReader(new StringReader("[" + json + "]")).readArray().get(0);
    }

    private static AnswerScoring.Score score(final ExtractionField field, final List<String> values,
        final String expected)
    {
        return AnswerScoring.score(field, values, expected == null ? null : parseJson(expected));
    }

    @Test
    void scoresNothingWithoutAnExpectedAnswer()
    {
        assertFalse(score(TestFields.createField(KEY, "text"), List.of("x"), null).scored());
        assertFalse(score(TestFields.createField(KEY, "text"), List.of("x"), "null").scored());
    }

    @Test
    void countsAMissingAnswerAsWrong()
    {
        final AnswerScoring.Score score = score(TestFields.createField(KEY, "text"), List.of(), "\"A title\"");

        assertTrue(score.scored());
        assertFalse(score.correct());
        assertEquals(0.0, score.f1());
    }

    @Test
    void acceptsAFreeTextThatSharesMostWords()
    {
        assertTrue(score(TestFields.createField(KEY, "text"), List.of("A Trial of Sardines in Prediabetes"),
            "\"Sardine trial: a trial of sardines in prediabetes\"").correct());
    }

    // Three words of five on each side make an F1 of exactly the threshold
    @Test
    void acceptsAFreeTextRightAtTheThreshold()
    {
        final AnswerScoring.Score score = score(TestFields.createField(KEY, "text"),
            List.of("alpha beta gamma delta epsilon"), "\"alpha beta gamma zeta eta\"");

        assertEquals(AnswerScoring.TEXT_MATCH_THRESHOLD, score.f1(), 1e-9);
        assertTrue(score.correct());
    }

    @Test
    void refusesAFreeTextJustBelowTheThreshold()
    {
        assertFalse(score(TestFields.createField(KEY, "text"), List.of("alpha beta gamma delta epsilon"),
            "\"alpha beta theta zeta eta\"").correct());
    }

    @Test
    void scoresAFreeTextWithNoWordsAsZero()
    {
        assertEquals(0.0, score(TestFields.createField(KEY, "text"), List.of("..."), "\"A trial\"").f1());
    }

    @Test
    void acceptsAnAlternativeText()
    {
        assertTrue(score(TestFields.createField(KEY, "text"), List.of("Sardines for prediabetes"),
            "{\"value\": \"The PREDIRCAM sardine-enriched diet trial\","
            + " \"alternatives\": [\"Sardines for Prediabetes\"]}").correct());
    }

    @Test
    void acceptsTheSameNumber()
    {
        assertTrue(score(TestFields.createField(KEY, ExtractionField.LONG_TYPE), List.of("182"), "182").correct());
    }

    @Test
    void refusesANearbyNumberWithoutATolerance()
    {
        assertFalse(score(TestFields.createField(KEY, ExtractionField.LONG_TYPE), List.of("180"), "182").correct());
    }

    @Test
    void acceptsANumberWithinItsTolerance()
    {
        assertTrue(score(TestFields.createField(KEY, ExtractionField.LONG_TYPE), List.of("200"),
            "{\"value\": 182, \"tolerance\": 0.25}").correct());
    }

    @Test
    void refusesANumberWrittenInWords()
    {
        assertFalse(score(TestFields.createField(KEY, ExtractionField.LONG_TYPE), List.of("about 180"), "182")
            .correct());
    }

    @Test
    void refusesANumberWhenTextIsExpected()
    {
        assertFalse(score(TestFields.createField(KEY, ExtractionField.LONG_TYPE), List.of("182"), "\"182\"")
            .correct());
    }

    @Test
    void comparesAnOptionExactly()
    {
        assertTrue(score(TestFields.createChoiceField(KEY, KINDS), List.of("device"), "\"device\"").correct());
        assertFalse(score(TestFields.createChoiceField(KEY, KINDS), List.of("drug"), "\"device\"").correct());
    }

    @Test
    void comparesAYesOrNoExactly()
    {
        assertTrue(score(TestFields.createField(KEY, ExtractionField.BOOLEAN_TYPE), List.of("true"), "true").correct());
        assertFalse(score(TestFields.createField(KEY, ExtractionField.BOOLEAN_TYPE), List.of("false"), "true")
            .correct());
    }

    @Test
    void comparesSeveralOptionsAsSets()
    {
        final ExtractionField kinds = TestFields.createMultipleChoiceField(KEY, KINDS);

        final AnswerScoring.Score same = score(kinds, List.of("device", "drug"), "[\"drug\", \"device\"]");
        assertTrue(same.correct());
        assertEquals(1.0, same.f1());
        final AnswerScoring.Score partly = score(kinds, List.of("drug"), "[\"drug\", \"device\"]");
        assertFalse(partly.correct());
        assertEquals(2.0 / 3, partly.f1(), 1e-9);
        assertEquals(0.0, score(kinds, List.of("other"), "[\"drug\"]").f1());
    }

    @Test
    void comparesSeveralOptionsIgnoringCase()
    {
        assertTrue(score(TestFields.createMultipleChoiceField(KEY, KINDS), List.of("Drug"), "[\"drug\"]").correct());
    }

    @Test
    void comparesSeveralFreeTextsWordByWord()
    {
        final ExtractionField sites = TestFields.createListField(KEY, ExtractionField.TEXT_TYPE);

        assertTrue(score(sites, List.of("Toronto General Hospital", "Mount Sinai"),
            "[\"Mount Sinai Hospital\", \"Toronto General\"]").correct());
        final AnswerScoring.Score partly = score(sites, List.of("Toronto General Hospital"),
            "[\"Toronto General\", \"Mount Sinai\"]");
        assertFalse(partly.correct());
        assertEquals(2.0 / 3, partly.f1(), 1e-9);
        assertEquals(0.0, score(sites, List.of("Kingston"), "[\"Toronto General\"]").f1());
    }

    // A protocol can name no intervention kind, and a model that finds none then got it right
    @Test
    void countsFindingNothingRightWhenNothingIsExpected()
    {
        assertTrue(score(TestFields.createMultipleChoiceField(KEY, KINDS), List.of(), "[]").correct());
        assertTrue(score(TestFields.createField(KEY, "text"), List.of(), "\"\"").correct());
        assertFalse(score(TestFields.createMultipleChoiceField(KEY, KINDS), List.of("drug"), "[]").correct());
    }
}
