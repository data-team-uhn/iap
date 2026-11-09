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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExtractionReply}: what of a reply becomes an answer, and what its confidence becomes.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ExtractionReplyTest
{
    private static final DocumentScan DOCUMENT = DocumentScan.of("# Protocol\n\n<!-- page: 1 -->\n\n"
        + "## Design\n\nThis is a randomised controlled trial of sardines.\n\n"
        + "## Population\n\nWe will enrol 182 adults with prediabetes.\n");

    private static final String REAL = "This is a randomised controlled trial of sardines.";

    private static final String INVENTED = "Participants were randomised to receive placebo or sardines";

    private static final List<ExtractionField.Option> DRUG_OR_DEVICE = List.of(
        new ExtractionField.Option("drug", "Drug", null), new ExtractionField.Option("device", "Medical device", null));

    private static ExtractionField field(final String key, final boolean multiple,
        final List<ExtractionField.Option> options)
    {
        return new ExtractionField(key, "Q?", null, null, "Find it.",
            new ExtractionField.Answer("text", multiple, null, null, null, options));
    }

    private static FieldResult readOne(final ExtractionField field, final String entry) throws Exception
    {
        return ExtractionReply.read(List.of(field), "{\"" + field.key() + "\": " + entry + "}", DOCUMENT)
            .get(field.key());
    }

    private static FieldResult readValue(final String value) throws Exception
    {
        return readOne(field("k", false, List.of()), "{\"evidence\": [], \"reasoning\": \"\", \"value\": " + value
            + ", \"confidence\": 0.5}");
    }

    private static FieldResult readWithQuotes(final double confidence, final String... quotes) throws Exception
    {
        final StringBuilder evidence = new StringBuilder();
        for (final String quote : quotes) {
            evidence.append(evidence.isEmpty() ? "" : ",").append("{\"quote\": \"").append(quote)
                .append("\", \"page\": 1}");
        }
        return readOne(field("k", false, List.of()), "{\"evidence\": [" + evidence + "], \"value\": \"yes\","
            + " \"confidence\": " + confidence + "}");
    }

    @Test
    void readsAnAnswerWithTheQuotesFoundAndWhereTheyAre() throws Exception
    {
        final FieldResult reply = readOne(field("design", false, List.of()), "{\"evidence\": ["
            + "{\"quote\": \"" + REAL + "\", \"page\": 1},"
            + "{\"quote\": \"" + INVENTED + "\", \"page\": 2}],"
            + "\"reasoning\": \" The design section says so. \", \"value\": \"randomised controlled trial\","
            + "\"confidence\": 0.9}");

        assertEquals("design", reply.key());
        assertEquals(List.of("randomised controlled trial"), reply.values());
        assertTrue(reply.found());
        assertEquals("The design section says so.", reply.reasoning());
        // The invented quote is dropped, but still counts as offered, and costs a quarter of the confidence
        assertEquals(List.of(new FieldResult.Passage(REAL, 1L, "Design", -1)), reply.passages());
        assertEquals(2, reply.quotesOffered());
        assertEquals(0.9 * 0.75, reply.confidence(), 1e-9);
    }

    @Test
    void keepsTheConfidenceWhenEveryQuoteIsFound() throws Exception
    {
        assertEquals(0.9, readWithQuotes(0.9, REAL).confidence(), 1e-9);
    }

    // No quotes at all costs the same as quotes that were all not found
    @Test
    void halvesTheConfidenceOfAnAnswerWithNoQuoteFound() throws Exception
    {
        assertEquals(0.45, readWithQuotes(0.9, INVENTED).confidence(), 1e-9);
        assertEquals(0.45, readWithQuotes(0.9).confidence(), 1e-9);
    }

    // Repeating a quote cannot raise the score, nor count as a second quote that failed
    @Test
    void countsAQuoteGivenTwiceOnceAndSkipsWhatIsNotAQuote() throws Exception
    {
        final FieldResult reply = readOne(field("size", false, List.of()), "{\"evidence\": ["
            + "{\"quote\": \"We will enrol 182 adults\", \"page\": 1.5},"
            + "{\"quote\": \" We will ENROL 182   adults \", \"page\": 1},"
            + "{\"quote\": \"randomised controlled trial of sardines\", \"page\": null},"
            + "{\"quote\": \"  \"}, {\"quote\": 7}, \"not an object\"],"
            + "\"reasoning\": 3, \"value\": \"182\", \"confidence\": 0.8}");

        // A page that is not a whole number is as good as none
        assertEquals(List.of(new FieldResult.Passage("We will enrol 182 adults", null, "Population", -1),
            new FieldResult.Passage("randomised controlled trial of sardines", null, "Design", -1)),
            reply.passages());
        assertEquals(2, reply.quotesOffered());
        assertEquals(0.8, reply.confidence(), 1e-9);
        // A reasoning of the wrong type is as good as none
        assertEquals("", reply.reasoning());
    }

    // With several documents sent together, each quote remembers which one it came from
    @Test
    void recordsWhichDocumentAQuoteCameFrom() throws Exception
    {
        final DocumentScan joined = DocumentScan.join(List.of("Preamble", "Protocol"),
            List.of("We serve the north.", "We will enrol 182 adults."));
        final ExtractionField size = field("size", false, List.of());

        final FieldResult reply = ExtractionReply.read(List.of(size), "{\"size\": {\"evidence\": ["
            + "{\"quote\": \"We will enrol 182 adults.\", \"page\": null}], \"value\": \"182\"}}", joined)
            .get("size");

        assertEquals(1, reply.passages().get(0).part());
    }

    @Test
    void readsNoEvidenceWhenThereIsNoListOfIt() throws Exception
    {
        final FieldResult reply = readOne(field("k", false, List.of()), "{\"evidence\": \"none\", \"value\": \"x\"}");

        assertTrue(reply.passages().isEmpty());
        assertEquals(0, reply.quotesOffered());
    }

    @Test
    void turnsTypedValuesIntoText() throws Exception
    {
        assertEquals(List.of("182"), readValue("182").values());
        assertEquals(List.of("2.5"), readValue("2.50").values());
        assertEquals(List.of("true"), readValue("true").values());
        assertEquals(List.of("false"), readValue("false").values());
        assertEquals(List.of("a title"), readValue("\" a title \"").values());
    }

    // A blank value counts as not found, or it would be saved as an answer and block the question for good
    @Test
    void findsNoAnswerInNullOrBlankOrSomethingElse() throws Exception
    {
        for (final String nothing : List.of("null", "\"  \"", "{\"a\": 1}", "[]", "[null, \" \"]")) {
            final FieldResult reply = readValue(nothing);
            assertTrue(reply.values().isEmpty(), nothing);
            assertFalse(reply.found(), nothing);
            assertEquals(0.5, reply.confidence(), 1e-9, "a not-found answer has nothing to back: " + nothing);
        }
    }

    @Test
    void readsAListAsOneValuePerItem() throws Exception
    {
        assertEquals(List.of("Toronto General", "Mount Sinai, Toronto", "3"), readOne(field("sites", true, List.of()),
            "{\"value\": [\"Toronto General\", \"Mount Sinai, Toronto\", 3]}").values());
    }

    // Picking one of several values for a question taking one would be a guess
    @Test
    void findsNoAnswerInSeveralValuesForAQuestionTakingOne() throws Exception
    {
        final FieldResult reply = readOne(field("kind", false, DRUG_OR_DEVICE),
            "{\"value\": [\"drug\", \"device\"], \"confidence\": 0.9}");

        assertFalse(reply.found());
        assertEquals(List.of("drug"), readOne(field("kind", false, DRUG_OR_DEVICE), "{\"value\": [\"drug\"]}")
            .values(), "a list of one is still one answer");
    }

    @Test
    void matchesAValueToTheOptionItNames() throws Exception
    {
        final ExtractionField choice = field("kind", true, DRUG_OR_DEVICE);

        assertEquals(List.of("drug", "drug", "device", "a vaccine"), readOne(choice,
            "{\"value\": [\"drug\", \"DRUG\", \"medical device\", \"a vaccine\"]}").values());
    }

    // Two options that read the same leave nothing to tell them apart, so the model's words are kept
    @Test
    void keepsAValueThatNamesTwoOptionsAsWritten() throws Exception
    {
        final ExtractionField choice = field("kind", false, List.of(new ExtractionField.Option("a", "Same", null),
            new ExtractionField.Option("b", "Same", null), new ExtractionField.Option("c", "C", null),
            new ExtractionField.Option("c", "C again", null)));

        assertEquals(List.of("same"), readOne(choice, "{\"value\": \"same\"}").values());
        assertEquals(List.of("c"), readOne(choice, "{\"value\": \"C\"}").values(), "one value twice is one option");
    }

    // The schema requires every question, so a reply that leaves one out did not follow it
    @Test
    void refusesAReplyThatLeavesAQuestionOut()
    {
        final ExtractionReply.UnreadableReply refused = assertThrows(ExtractionReply.UnreadableReply.class,
            () -> ExtractionReply.read(List.of(field("asked", false, List.of()), field("forgotten", false, List.of()),
                field("odd", false, List.of())), "{\"asked\": {\"value\": \"x\"}, \"odd\": \"not an object\"}",
                DOCUMENT));

        assertEquals("it left out forgotten, odd", refused.getMessage());
        assertFalse(refused.isCutOff());
    }

    @Test
    void holdsTheConfidenceBetweenZeroAndOne() throws Exception
    {
        assertEquals(1.0, readOne(field("k", false, List.of()), "{\"confidence\": 7}").confidence());
        assertEquals(0.0, readOne(field("k", false, List.of()), "{\"confidence\": -1}").confidence());
        assertEquals(0.0, readOne(field("k", false, List.of()), "{\"confidence\": \"high\"}").confidence());
    }

    @Test
    void findsTheObjectWhereverTheModelPutIt() throws Exception
    {
        final Map<String, FieldResult> replies = ExtractionReply.read(List.of(field("k", false,
            List.of())), "Here you go:\n```json\n{\"k\": {\"value\": \"x\"}}\n```", DOCUMENT);

        assertEquals(List.of("x"), replies.get("k").values());
    }

    @Test
    void saysWhyAReplyCannotBeRead()
    {
        final List<ExtractionField> fields = List.of(field("k", false, List.of()));
        final Map<String, String> reasons = new LinkedHashMap<>();
        reasons.put(null, "it was empty");
        reasons.put(" \n", "it was empty");
        reasons.put("I cannot answer that.", "it held no JSON object");
        reasons.put("} backwards {", "it held no JSON object");
        reasons.put("{\"k\": [1, }", "it held no JSON object");
        reasons.forEach((reply, reason) -> assertEquals(reason, assertThrows(ExtractionReply.UnreadableReply.class,
            () -> ExtractionReply.read(fields, reply, DOCUMENT), String.valueOf(reply)).getMessage()));
    }

    // Evidence comes first in every answer, so a reply that ran out of room has closed some objects already
    @Test
    void tellsAReplyThatRanOutOfRoom()
    {
        final ExtractionReply.UnreadableReply refused = assertThrows(ExtractionReply.UnreadableReply.class,
            () -> ExtractionReply.read(List.of(field("a", false, List.of()), field("b", false, List.of())),
                "{\"a\": {\"evidence\": [{\"quote\": \"x\", \"page\": 1}], \"value\": \"y\"},"
                    + " \"b\": {\"evidence\": [{\"quo", DOCUMENT));

        assertEquals("it was cut off before the end", refused.getMessage());
        assertTrue(refused.isCutOff());
    }

    // Written out in full, such a number would take a billion characters
    @Test
    void findsNoAnswerInANumberTooLongToWrite() throws Exception
    {
        for (final String huge : List.of("1e999999999", "1e-999999999", "1" + "0".repeat(101))) {
            assertTrue(readValue(huge).values().isEmpty(), huge);
        }
        assertEquals(List.of("1" + "0".repeat(99)), readValue("1e99").values());
    }
}
