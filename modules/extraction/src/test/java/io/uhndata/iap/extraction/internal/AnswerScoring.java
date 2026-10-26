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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Compares an extracted answer with the expected one, the way each kind of answer has to be compared.
 *
 * <p>An option, a yes or no and a number are right or wrong. A list of options is right when it names the same
 * options, and its F1 says how close it came. Free text is compared word by word: it is right when the F1 of its
 * words against the expected text, or any accepted alternative, reaches {@link #TEXT_MATCH}.</p>
 *
 * <p>An expected answer is a JSON value, or an object giving one under {@code value}. That object may add
 * {@code alternatives} that also count and, for a number, a relative {@code tolerance}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class AnswerScoring
{
    /** How many of its words free text must share with the expected text to count as the same answer. */
    static final double TEXT_MATCH = 0.6;

    private static final Pattern WORD_BREAK = Pattern.compile("[^\\p{L}\\p{N}]+");

    private AnswerScoring()
    {
        // Utility class, not meant to be instantiated
    }

    /**
     * How an answer compares with the expected one.
     *
     * @param scored whether there was an expected answer to compare with
     * @param correct whether the answer counts as right
     * @param f1 how close it came, from 0 to 1
     * @version $Id$
     * @since 0.1.0
     */
    record Score(boolean scored, boolean correct, double f1)
    {
        static final Score UNSCORED = new Score(false, false, 0.0);
    }

    /**
     * Compare one answer.
     *
     * @param field the question
     * @param values what was extracted, empty when nothing was
     * @param expected the expected answer, or {@code null} when there is none
     * @return the score
     */
    static Score score(final ExtractionField field, final List<String> values, final JsonValue expected)
    {
        if (expected == null || expected.getValueType() == JsonValue.ValueType.NULL) {
            return Score.UNSCORED;
        }
        return values.isEmpty() ? new Score(true, false, 0.0) : compare(field.answer(), values, expected);
    }

    private static Score compare(final ExtractionField.Answer answer, final List<String> values,
        final JsonValue expected)
    {
        final JsonValue value = expected instanceof JsonObject && ((JsonObject) expected).containsKey("value")
            ? ((JsonObject) expected).get("value") : expected;
        if (answer.multiple()) {
            final double f1 = f1(new HashSet<>(values), new HashSet<>(texts(value)));
            return new Score(true, f1 == 1.0, f1);
        }
        if (ExtractionField.LONG.equals(answer.dataType()) || ExtractionField.DOUBLE.equals(answer.dataType())) {
            return exactly(isWithin(values.get(0), value, tolerance(expected)));
        }
        if (!answer.options().isEmpty() || ExtractionField.BOOLEAN.equals(answer.dataType())) {
            return exactly(texts(value).stream().anyMatch(text -> text.equalsIgnoreCase(values.get(0))));
        }
        return compareText(values, value, expected);
    }

    private static Score exactly(final boolean right)
    {
        return new Score(true, right, right ? 1.0 : 0.0);
    }

    private static Score compareText(final List<String> values, final JsonValue value, final JsonValue expected)
    {
        final List<String> accepted = new ArrayList<>(texts(value));
        if (expected instanceof JsonObject && ((JsonObject) expected).get("alternatives") instanceof JsonArray) {
            accepted.addAll(texts(((JsonObject) expected).get("alternatives")));
        }
        final Set<String> found = words(String.join(" ", values));
        final double f1 = accepted.stream().mapToDouble(text -> f1(found, words(text))).max().orElse(0.0);
        return new Score(true, f1 >= TEXT_MATCH, f1);
    }

    private static boolean isWithin(final String extracted, final JsonValue expected, final double tolerance)
    {
        try {
            final double actual = Double.parseDouble(extracted);
            final double wanted = ((JsonNumber) expected).doubleValue();
            return Math.abs(actual - wanted) <= tolerance * Math.abs(wanted);
        } catch (final NumberFormatException | ClassCastException e) {
            return false;
        }
    }

    private static double tolerance(final JsonValue expected)
    {
        return expected instanceof JsonObject && ((JsonObject) expected).get("tolerance") instanceof JsonNumber
            ? ((JsonObject) expected).getJsonNumber("tolerance").doubleValue() : 0.0;
    }

    /** The expected value as text, one entry per item of a list. */
    private static List<String> texts(final JsonValue value)
    {
        if (value instanceof JsonArray) {
            final List<String> texts = new ArrayList<>();
            ((JsonArray) value).forEach(item -> texts.addAll(texts(item)));
            return texts;
        }
        if (value instanceof JsonString) {
            return List.of(((JsonString) value).getString());
        }
        return value == null || value.getValueType() == JsonValue.ValueType.NULL ? List.of()
            : List.of(value.toString());
    }

    private static Set<String> words(final String text)
    {
        final Set<String> words = new HashSet<>(Arrays.asList(WORD_BREAK.split(text.toLowerCase(Locale.ROOT))));
        words.remove("");
        return words;
    }

    private static double f1(final Set<String> found, final Set<String> wanted)
    {
        if (found.isEmpty() || wanted.isEmpty()) {
            return 0.0;
        }
        final Set<String> common = new HashSet<>(found);
        common.retainAll(wanted);
        if (common.isEmpty()) {
            return 0.0;
        }
        final double precision = (double) common.size() / found.size();
        final double recall = (double) common.size() / wanted.size();
        return 2 * precision * recall / (precision + recall);
    }
}
