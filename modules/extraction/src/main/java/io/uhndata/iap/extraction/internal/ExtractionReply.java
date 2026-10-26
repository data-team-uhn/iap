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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads one call's reply into an answer per question, keeping only the quotes that are really in the document.
 *
 * <p>The schema asks for typed values. What comes back is stored as text, so a number, a boolean or a list of
 * either is turned into the strings an answer holds. A provider that ignores the schema may name an option by its
 * label, or in another case. Such a value is matched to the option it names. A value that names no option is kept in
 * the model's own words, for a person to see and correct.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ExtractionReply
{
    private ExtractionReply()
    {
        // Utility class, not meant to be instantiated
    }

    /**
     * A quote that was found in the document.
     *
     * @param text the quote as the model gave it
     * @param page the page the model said it is on, or {@code null}
     * @param heading the nearest heading above it in the document, empty when nothing titles it
     * @version $Id$
     * @since 0.1.0
     */
    record Quote(@NotNull String text, @Nullable Integer page, @NotNull String heading)
    {
    }

    /**
     * What the model answered for one question.
     *
     * @param key the question's key
     * @param values the answer, empty when the model found none
     * @param reasoning how the model got there, or what it looked for
     * @param confidence how sure the model said it was, from 0 to 1
     * @param quotes the quotes that were found in the document
     * @param quotesOffered how many distinct quotes the model gave, found or not
     * @version $Id$
     * @since 0.1.0
     */
    record FieldReply(@NotNull String key, @NotNull List<String> values, @NotNull String reasoning, double confidence,
        @NotNull List<Quote> quotes, int quotesOffered)
    {
        /**
         * Whether the model gave an answer.
         *
         * @return {@code true} when there is at least one value
         */
        boolean isFound()
        {
            return !this.values.isEmpty();
        }
    }

    /**
     * Read a reply.
     *
     * @param fields the questions the call asked
     * @param reply what the model said
     * @param document the document the quotes are checked against
     * @return an answer per question, in the order they were asked, or {@code null} when the reply holds no JSON
     *         object; a question the reply leaves out is answered with nothing
     */
    @Nullable
    static Map<String, FieldReply> read(@NotNull final List<ExtractionField> fields, @Nullable final String reply,
        @NotNull final DocumentScan document)
    {
        final JsonObject object = readJsonObject(reply);
        if (object == null) {
            return null;
        }
        final Map<String, FieldReply> answers = new LinkedHashMap<>();
        for (final ExtractionField field : fields) {
            final JsonValue entry = object.get(field.key());
            answers.put(field.key(), entry instanceof JsonObject
                ? readField(field, (JsonObject) entry, document)
                : new FieldReply(field.key(), List.of(), "", 0.0, List.of(), 0));
        }
        return answers;
    }

    /**
     * Why a reply could not be read, for the correction sent back to the model.
     *
     * @param reply what the model said
     * @return the reason, or {@code null} when the reply can be read
     */
    @Nullable
    static String describeUnreadable(@Nullable final String reply)
    {
        if (reply == null || reply.isBlank()) {
            return "it was empty";
        }
        if (readJsonObject(reply) != null) {
            return null;
        }
        return reply.indexOf('{') >= 0 && reply.lastIndexOf('}') < reply.indexOf('{')
            ? "it was cut off before the end" : "it held no JSON object";
    }

    /** The JSON object in a reply, wherever the model put it, or {@code null} when there is none. */
    private static JsonObject readJsonObject(final String reply)
    {
        if (reply == null) {
            return null;
        }
        final int start = reply.indexOf('{');
        final int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try (JsonReader reader = Json.createReader(new StringReader(reply.substring(start, end + 1)))) {
            return reader.readObject();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static FieldReply readField(final ExtractionField field, final JsonObject entry,
        final DocumentScan document)
    {
        final List<String> values = readValues(entry.get(ResponseSchema.VALUE)).stream()
            .map(value -> matchOption(field, value)).toList();
        final Set<String> offered = new LinkedHashSet<>();
        final List<Quote> quotes = new ArrayList<>();
        final JsonValue evidence = entry.get(ResponseSchema.EVIDENCE);
        if (evidence instanceof JsonArray) {
            for (final JsonValue item : (JsonArray) evidence) {
                readQuote(item, document, offered, quotes);
            }
        }
        final JsonValue reasoning = entry.get(ResponseSchema.REASONING);
        return new FieldReply(field.key(), values,
            reasoning instanceof JsonString ? ((JsonString) reasoning).getString().strip() : "",
            readConfidence(entry.get(ResponseSchema.CONFIDENCE)), quotes, offered.size());
    }

    /**
     * Check one quote, adding it to the offered ones and, when it is in the document, to the found ones. A quote
     * given twice counts once.
     */
    private static void readQuote(final JsonValue item, final DocumentScan document, final Set<String> offered,
        final List<Quote> quotes)
    {
        if (!(item instanceof JsonObject)) {
            return;
        }
        final JsonValue quote = ((JsonObject) item).get(ResponseSchema.QUOTE);
        if (!(quote instanceof JsonString) || ((JsonString) quote).getString().isBlank()
            || !offered.add(((JsonString) quote).getString().strip())) {
            return;
        }
        final String text = ((JsonString) quote).getString().strip();
        final int at = document.locate(text);
        if (at >= 0) {
            final JsonValue page = ((JsonObject) item).get(ResponseSchema.PAGE);
            quotes.add(new Quote(text, page instanceof JsonNumber && ((JsonNumber) page).isIntegral()
                ? ((JsonNumber) page).intValue() : null, document.getHeading(at)));
        }
    }

    /** The values a reply gives, as text: one for a single answer, one per item for a list, none for null. */
    private static List<String> readValues(final JsonValue value)
    {
        if (value instanceof JsonArray) {
            final List<String> values = new ArrayList<>();
            ((JsonArray) value).forEach(item -> values.addAll(readValues(item)));
            return values;
        }
        final String text = readValue(value);
        return text == null || text.isBlank() ? List.of() : List.of(text.strip());
    }

    private static String readValue(final JsonValue value)
    {
        if (value instanceof JsonString) {
            return ((JsonString) value).getString();
        } else if (value instanceof JsonNumber) {
            final JsonNumber number = (JsonNumber) value;
            return number.isIntegral() ? number.bigIntegerValue().toString()
                : number.bigDecimalValue().stripTrailingZeros().toPlainString();
        } else if (JsonValue.TRUE.equals(value) || JsonValue.FALSE.equals(value)) {
            return value.toString();
        }
        return null;
    }

    /** The option a value names, matched by value and then by label, ignoring case; the value itself otherwise. */
    private static String matchOption(final ExtractionField field, final String value)
    {
        for (final ExtractionField.Option option : field.answer().options()) {
            if (option.value().equals(value)) {
                return option.value();
            }
        }
        return field.answer().options().stream()
            .filter(option -> option.value().equalsIgnoreCase(value) || option.label().equalsIgnoreCase(value))
            .map(ExtractionField.Option::value).findFirst().orElse(value);
    }

    /** A confidence, held to 0..1; 0 when the model gave none. */
    private static double readConfidence(final JsonValue value)
    {
        if (!(value instanceof JsonNumber)) {
            return 0.0;
        }
        final BigDecimal confidence = ((JsonNumber) value).bigDecimalValue();
        return Math.max(0.0, Math.min(1.0, confidence.doubleValue()));
    }
}
