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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static FieldResult readOne(final ExtractionField field, final String entry) throws Exception
    {
        return ExtractionReply.read(List.of(field), "{\"" + field.key() + "\": " + entry + "}", DOCUMENT)
            .get(field.key());
    }

    private static FieldResult readValue(final String value) throws Exception
    {
        return readOne(TestFields.createTextField("k"), "{\"evidence\": [], \"reasoning\": \"\", \"value\": " + value
            + ", \"confidence\": 0.5}");
    }

    private static FieldResult readWithQuotes(final double confidence, final String... quotes) throws Exception
    {
        final StringBuilder evidence = new StringBuilder();
        for (final String quote : quotes) {
            evidence.append(evidence.isEmpty() ? "" : ",").append("{\"quote\": \"").append(quote)
                .append("\", \"page\": 1}");
        }
        return readOne(TestFields.createTextField("k"), "{\"evidence\": [" + evidence + "], \"value\": \"yes\","
            + " \"confidence\": " + confidence + "}");
    }

    @Test
    void readsAnAnswerWithTheQuotesFoundAndWhereTheyAre() throws Exception
    {
        final FieldResult reply = readOne(TestFields.createTextField("design"), "{\"evidence\": ["
            + "{\"quote\": \"" + REAL + "\", \"page\": 1},"
            + "{\"quote\": \"" + INVENTED + "\", \"page\": 2}],"
            + "\"value\": \"randomised controlled trial\", \"confidence\": 0.9}");

        assertEquals("design", reply.key());
        assertEquals(List.of("randomised controlled trial"), reply.values());
        assertTrue(reply.isFound());
        assertEquals(List.of(new FieldResult.Passage(REAL, 1L, "Design", -1)), reply.passages());
        assertEquals(2, reply.quotesOffered(), "the invented quote is dropped, but still counts as offered");
    }

    @Test
    void trimsTheReasoning() throws Exception
    {
        final FieldResult reply = readOne(TestFields.createTextField("k"),
            "{\"reasoning\": \" The design section says so. \", \"value\": \"x\"}");

        assertEquals("The design section says so.", reply.reasoning());
    }

    @Test
    void readsNoReasoningOfTheWrongType() throws Exception
    {
        assertEquals("", readOne(TestFields.createTextField("k"), "{\"reasoning\": 3, \"value\": \"x\"}").reasoning());
    }

    // Half the quotes found keeps half of what the penalty would take. The model's own number stays as it gave it.
    @Test
    void cutsTheConfidenceForEachQuoteNotFound() throws Exception
    {
        final FieldResult reply = readWithQuotes(0.9, REAL, INVENTED);

        assertEquals(0.9 * (1 + ExtractionReply.UNVERIFIED_PENALTY) / 2, reply.confidence(), 1e-9);
        assertEquals(0.9, reply.modelConfidence(), 1e-9);
    }

    @Test
    void keepsTheConfidenceWhenEveryQuoteIsFound() throws Exception
    {
        assertEquals(0.9, readWithQuotes(0.9, REAL).confidence(), 1e-9);
    }

    // No quotes at all costs the same as quotes that were all not found
    @Test
    void cutsTheConfidenceOfAnAnswerWithNoQuoteFound() throws Exception
    {
        assertEquals(0.9 * ExtractionReply.UNVERIFIED_PENALTY, readWithQuotes(0.9, INVENTED).confidence(), 1e-9);
        assertEquals(0.9 * ExtractionReply.UNVERIFIED_PENALTY, readWithQuotes(0.9).confidence(), 1e-9);
    }

    // Repeating a quote cannot raise the score, nor count as a second quote that failed
    @Test
    void countsAQuoteGivenTwiceOnceAndSkipsWhatIsNotAQuote() throws Exception
    {
        final FieldResult reply = readOne(TestFields.createTextField("size"), "{\"evidence\": ["
            + "{\"quote\": \"We will enrol 182 adults\", \"page\": 1},"
            + "{\"quote\": \" We will ENROL 182   adults \", \"page\": 1},"
            + "{\"quote\": \"randomised controlled trial of sardines\", \"page\": null},"
            + "{\"quote\": \"  \"}, {\"quote\": 7}, \"not an object\"],"
            + "\"value\": \"182\", \"confidence\": 0.8}");

        assertEquals(List.of(new FieldResult.Passage("We will enrol 182 adults", 1L, "Population", -1),
            new FieldResult.Passage("randomised controlled trial of sardines", null, "Design", -1)),
            reply.passages());
        assertEquals(2, reply.quotesOffered());
        assertEquals(0.8, reply.confidence(), 1e-9);
    }

    @Test
    void readsNoPageThatIsNotAWholeNumber() throws Exception
    {
        final FieldResult reply = readOne(TestFields.createTextField("size"), "{\"evidence\": ["
            + "{\"quote\": \"We will enrol 182 adults\", \"page\": 1.5}], \"value\": \"182\"}");

        assertNull(reply.passages().get(0).page());
    }

    // With several documents sent together, each quote remembers which one it came from
    @Test
    void recordsWhichDocumentAQuoteCameFrom() throws Exception
    {
        final DocumentScan joined = DocumentScan.join(List.of("Preamble", "Protocol"),
            List.of("We serve the north.", "We will enrol 182 adults."));
        final ExtractionField size = TestFields.createTextField("size");

        final FieldResult reply = ExtractionReply.read(List.of(size), "{\"size\": {\"evidence\": ["
            + "{\"quote\": \"We will enrol 182 adults.\", \"page\": null}], \"value\": \"182\"}}", joined)
            .get("size");

        assertEquals(1, reply.passages().get(0).part());
    }

    @Test
    void readsNoEvidenceWhenThereIsNoListOfIt() throws Exception
    {
        final FieldResult reply =
            readOne(TestFields.createTextField("k"), "{\"evidence\": \"none\", \"value\": \"x\"}");

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

    // A blank value counts as not found, so no suggestion is made from it
    @Test
    void findsNoAnswerInNullOrBlankOrSomethingElse() throws Exception
    {
        for (final String nothing : List.of("null", "\"  \"", "{\"a\": 1}", "[]", "[null, \" \"]")) {
            final FieldResult reply = readValue(nothing);
            assertTrue(reply.values().isEmpty(), nothing);
            assertFalse(reply.isFound(), nothing);
            assertEquals(0.5, reply.confidence(), 1e-9, "a not-found answer has nothing to back: " + nothing);
        }
    }

    @Test
    void readsAListAsOneValuePerItem() throws Exception
    {
        final ExtractionField sites = TestFields.createListField("sites", ExtractionField.TEXT_TYPE);

        assertEquals(List.of("Toronto General", "Mount Sinai, Toronto", "3"),
            readOne(sites, "{\"value\": [\"Toronto General\", \"Mount Sinai, Toronto\", 3]}").values());
    }

    // Picking one of several values for a question taking one would be a guess
    @Test
    void findsNoAnswerInSeveralValuesForAQuestionTakingOne() throws Exception
    {
        final FieldResult reply = readOne(TestFields.createChoiceField("kind", TestFields.DRUG_OR_DEVICE),
            "{\"value\": [\"drug\", \"device\"], \"confidence\": 0.9}");

        assertFalse(reply.isFound());
    }

    @Test
    void readsAListOfOneAsOneAnswer() throws Exception
    {
        final ExtractionField kind = TestFields.createChoiceField("kind", TestFields.DRUG_OR_DEVICE);

        assertEquals(List.of("drug"), readOne(kind, "{\"value\": [\"drug\"]}").values());
    }

    @Test
    void matchesAValueToTheOptionItNames() throws Exception
    {
        final ExtractionField choice = TestFields.createMultipleChoiceField("kind", TestFields.DRUG_OR_DEVICE);

        assertEquals(List.of("drug", "drug", "device", "a vaccine"), readOne(choice,
            "{\"value\": [\"drug\", \"DRUG\", \"medical device\", \"a vaccine\"]}").values());
    }

    // Two options that read the same leave nothing to tell them apart, so the model's words are kept
    @Test
    void keepsAValueThatNamesTwoOptionsAsWritten() throws Exception
    {
        final ExtractionField choice = TestFields.createChoiceField("kind", List.of(
            new ExtractionField.Option("a", "Same", null), new ExtractionField.Option("b", "Same", null),
            new ExtractionField.Option("c", "C", null), new ExtractionField.Option("c", "C again", null)));

        assertEquals(List.of("same"), readOne(choice, "{\"value\": \"same\"}").values());
        assertEquals(List.of("c"), readOne(choice, "{\"value\": \"C\"}").values(), "one value twice is one option");
    }

    // A model without strict output may skip a question it cannot answer; the ones it did answer are kept
    @Test
    void countsAQuestionTheReplyLeavesOutAsNotFound() throws Exception
    {
        final Map<String, FieldResult> answers = ExtractionReply.read(List.of(TestFields.createTextField("asked"),
            TestFields.createTextField("forgotten"), TestFields.createTextField("odd")),
            "{\"asked\": {\"value\": \"x\"}, \"odd\": \"not an object\"}", DOCUMENT);

        assertEquals(List.of("x"), answers.get("asked").values());
        assertTrue(answers.get("forgotten").values().isEmpty());
        assertTrue(answers.get("odd").values().isEmpty());
    }

    @Test
    void refusesAReplyThatAnswersNoQuestion()
    {
        final ExtractionReply.UnreadableReply refused = assertThrows(ExtractionReply.UnreadableReply.class,
            () -> ExtractionReply.read(List.of(TestFields.createTextField("asked")), "{\"other\": {}}", DOCUMENT));

        assertEquals("it answered none of the questions", refused.getMessage());
        assertFalse(refused.isCutOff());
    }

    @Test
    void holdsTheConfidenceBetweenZeroAndOne() throws Exception
    {
        assertEquals(1.0, readOne(TestFields.createTextField("k"), "{\"confidence\": 7}").confidence());
        assertEquals(0.0, readOne(TestFields.createTextField("k"), "{\"confidence\": -1}").confidence());
        assertEquals(0.0, readOne(TestFields.createTextField("k"), "{\"confidence\": \"high\"}").confidence());
    }

    @Test
    void findsTheObjectWhereverTheModelPutIt() throws Exception
    {
        final Map<String, FieldResult> replies = ExtractionReply.read(List.of(TestFields.createTextField("k")),
            "Here you go:\n```json\n{\"k\": {\"value\": \"x\"}}\n```", DOCUMENT);

        assertEquals(List.of("x"), replies.get("k").values());
    }

    @Test
    void saysWhyAReplyCannotBeRead()
    {
        final List<ExtractionField> fields = List.of(TestFields.createTextField("k"));
        final Map<String, String> reasons = new LinkedHashMap<>();
        reasons.put(null, "it was empty");
        reasons.put(" \n", "it was empty");
        reasons.put("I cannot answer that.", "it held no JSON object");
        reasons.put("} backwards {", "it held no JSON object");
        reasons.put("{\"k\": [1, }", "it held no JSON object");
        reasons.forEach((reply, reason) -> assertEquals(reason, assertThrows(ExtractionReply.UnreadableReply.class,
            () -> ExtractionReply.read(fields, reply, DOCUMENT), String.valueOf(reply)).getMessage()));
    }

    // A reply that ran out of room has opened more objects than it closed
    @Test
    void tellsAReplyThatRanOutOfRoom()
    {
        final ExtractionReply.UnreadableReply refused = assertThrows(ExtractionReply.UnreadableReply.class,
            () -> ExtractionReply.read(List.of(TestFields.createTextField("a"), TestFields.createTextField("b")),
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
