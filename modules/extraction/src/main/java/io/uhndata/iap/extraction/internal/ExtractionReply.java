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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Reads one call's reply into an answer per question, keeping only the quotes that are really in the document.
 *
 * <p>The schema asks for typed values, but an answer is saved as text, so a number, a boolean or a list of either is
 * turned into strings. A provider that ignores the schema may name an option by its label, or in another case; such a
 * value is matched to the option it names. A value that names no option is kept in the model's own words, for a
 * person to see and correct.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ExtractionReply
{
    /**
     * What a found answer's confidence is multiplied by when none of its quotes are found, so 0.9 becomes 0.45. Not
     * 0, because the answer can still be right.
     */
    static final double UNVERIFIED_PENALTY = 0.5;

    private ExtractionReply()
    {
        // Utility
    }

    /**
     * Reads a reply.
     *
     * @param fields the questions the call asked
     * @param reply what the model said
     * @param document the document the quotes are checked against: the whole of it, not only the part sent
     * @return an answer per question, in the order asked, or {@code null} when the reply holds no JSON object; a
     *     question the reply leaves out is answered with nothing
     */
    static Map<String, FieldResult> read(final List<ExtractionField> fields, final String reply,
        final DocumentScan document)
    {
        final JsonObject object = ModelReplies.readJsonObject(reply);
        if (object == null) {
            return null;
        }
        final Map<String, FieldResult> answers = new LinkedHashMap<>();
        for (final ExtractionField field : fields) {
            final JsonValue entry = object.get(field.key());
            answers.put(field.key(), entry instanceof JsonObject
                ? readField(field, (JsonObject) entry, document) : FieldResult.createNotFound(field.key()));
        }
        return answers;
    }

    /**
     * Why a reply could not be read, for the note sent back to the model.
     *
     * @param reply what the model said
     * @return the reason, or {@code null} when the reply can be read
     */
    static String describeUnreadable(final String reply)
    {
        if (reply == null || reply.isBlank()) {
            return "it was empty";
        }
        if (ModelReplies.readJsonObject(reply) != null) {
            return null;
        }
        return reply.indexOf('{') >= 0 && reply.lastIndexOf('}') < reply.indexOf('{')
            ? "it was cut off before the end" : "it held no JSON object";
    }

    private static FieldResult readField(final ExtractionField field, final JsonObject entry,
        final DocumentScan document)
    {
        final List<String> values = readValues(entry.get(ResponseSchema.VALUE_KEY)).stream()
            .map(value -> matchOption(field.answer().options(), value)).toList();
        final Set<String> offered = new HashSet<>();
        final List<FieldResult.Passage> passages = new ArrayList<>();
        if (entry.get(ResponseSchema.EVIDENCE_KEY) instanceof JsonArray evidence) {
            evidence.forEach(item -> readQuote(item, document, offered, passages));
        }
        final JsonValue reasoning = entry.get(ResponseSchema.REASONING_KEY);
        final double claimed = ModelReplies.readConfidence(entry, ResponseSchema.CONFIDENCE_KEY);
        // A found answer pays for the quotes that are not in the document; a "not found" has nothing to back
        final double confidence = values.isEmpty() ? claimed
            : claimed * getEvidenceFactor(passages.size(), offered.size());
        return new FieldResult(field.key(), values,
            reasoning instanceof JsonString text ? text.getString().strip() : "", confidence, passages,
            offered.size());
    }

    /**
     * Checks one quote. It counts as offered, and when it is in the document it is kept with the heading above it
     * and the document it is in. The same quote given twice counts once, so repeating one cannot raise the score.
     */
    private static void readQuote(final JsonValue item, final DocumentScan document, final Set<String> offered,
        final List<FieldResult.Passage> passages)
    {
        if (!(item instanceof JsonObject quoted) || !(quoted.get(ResponseSchema.QUOTE_KEY) instanceof JsonString quote)
            || quote.getString().isBlank() || !offered.add(QuoteVerifier.normalize(quote.getString()))) {
            return;
        }
        final String text = quote.getString().strip();
        final int at = document.locate(text);
        if (at >= 0) {
            passages.add(new FieldResult.Passage(text, ModelReplies.readLong(quoted, ResponseSchema.PAGE_KEY),
                document.getHeading(at), document.getPart(at)));
        }
    }

    /**
     * What a found answer's confidence is multiplied by: {@link #UNVERIFIED_PENALTY} when no quote was found, up to
     * 1 when all were. An answer with no quotes at all gets {@link #UNVERIFIED_PENALTY} too.
     *
     * @param kept how many quotes were found in the document
     * @param offered how many different quotes the model gave
     * @return the multiplier
     */
    private static double getEvidenceFactor(final int kept, final int offered)
    {
        if (offered == 0) {
            return UNVERIFIED_PENALTY;
        }
        return UNVERIFIED_PENALTY + (1.0 - UNVERIFIED_PENALTY) * ((double) kept / offered);
    }

    /** The values a reply gives, as text: one for a single answer, one per item for a list, none for null. */
    private static List<String> readValues(final JsonValue value)
    {
        if (value instanceof JsonArray items) {
            final List<String> values = new ArrayList<>();
            items.forEach(item -> values.addAll(readValues(item)));
            return values;
        }
        final String text = readValue(value);
        return text == null || text.isBlank() ? List.of() : List.of(text.strip());
    }

    private static String readValue(final JsonValue value)
    {
        if (value instanceof JsonString text) {
            return text.getString();
        } else if (value instanceof JsonNumber number) {
            return number.isIntegral() ? number.bigIntegerValue().toString()
                : number.bigDecimalValue().stripTrailingZeros().toPlainString();
        } else if (JsonValue.TRUE.equals(value) || JsonValue.FALSE.equals(value)) {
            return value.toString();
        }
        return null;
    }

    /**
     * The option a value names: by value, then by label, ignoring case. A value that names no option, or names two
     * options that read the same, is kept as the model wrote it: picking one of two would risk saving the wrong one.
     */
    private static String matchOption(final List<ExtractionField.Option> options, final String value)
    {
        final String byValue = findUniqueMatch(options, option -> option.value().equalsIgnoreCase(value));
        if (byValue != null) {
            return byValue;
        }
        final String byLabel = findUniqueMatch(options, option -> option.label().equalsIgnoreCase(value));
        return byLabel != null ? byLabel : value;
    }

    /** The value of the one option matching {@code test}, or {@code null} when none or several differ. */
    private static String findUniqueMatch(final List<ExtractionField.Option> options,
        final Predicate<ExtractionField.Option> test)
    {
        String match = null;
        for (final ExtractionField.Option option : options) {
            if (test.test(option)) {
                if (match != null && !match.equals(option.value())) {
                    return null;
                }
                match = option.value();
            }
        }
        return match;
    }
}
