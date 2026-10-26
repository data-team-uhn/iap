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
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExtractionReply}: what of a reply becomes an answer.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ExtractionReplyTest
{
    private static final DocumentScan DOCUMENT = DocumentScan.of("# Protocol\n\n<!-- page: 1 -->\n\n"
        + "## Design\n\nThis is a randomised controlled trial of sardines.\n\n"
        + "## Population\n\nWe will enrol 182 adults with prediabetes.\n");

    private static final List<ExtractionField.Option> DRUG_OR_DEVICE = List.of(
        new ExtractionField.Option("drug", "Drug", null), new ExtractionField.Option("device", "Medical device", null));

    private static ExtractionField field(final String key, final boolean multiple,
        final List<ExtractionField.Option> options)
    {
        return new ExtractionField(key, "Q?", null, null, "Find it.",
            new ExtractionField.Answer("text", multiple, null, null, null, options));
    }

    private static ExtractionReply.FieldReply readOne(final ExtractionField field, final String entry)
    {
        return ExtractionReply.read(List.of(field), "{\"" + field.key() + "\": " + entry + "}", DOCUMENT)
            .get(field.key());
    }

    private static ExtractionReply.FieldReply readValue(final String value)
    {
        return readOne(field("k", false, List.of()), "{\"evidence\": [], \"reasoning\": \"\", \"value\": " + value
            + ", \"confidence\": 0.5}");
    }

    @Test
    void readsAnAnswerWithTheQuotesFoundAndWhereTheyAre()
    {
        final ExtractionReply.FieldReply reply = readOne(field("design", false, List.of()), "{\"evidence\": ["
            + "{\"quote\": \"This is a randomised controlled trial of sardines.\", \"page\": 1},"
            + "{\"quote\": \"Participants were randomised to receive placebo or sardines\", \"page\": 2}],"
            + "\"reasoning\": \" The design section says so. \", \"value\": \"randomised controlled trial\","
            + "\"confidence\": 0.9}");

        assertEquals("design", reply.key());
        assertEquals(List.of("randomised controlled trial"), reply.values());
        assertTrue(reply.isFound());
        assertEquals("The design section says so.", reply.reasoning());
        assertEquals(0.9, reply.confidence());
        // The invented quote is dropped, but still counts as offered
        assertEquals(List.of(new ExtractionReply.Quote("This is a randomised controlled trial of sardines.", 1,
            "Design")), reply.quotes());
        assertEquals(2, reply.quotesOffered());
    }

    @Test
    void countsAQuoteGivenTwiceOnceAndSkipsWhatIsNotAQuote()
    {
        final ExtractionReply.FieldReply reply = readOne(field("size", false, List.of()), "{\"evidence\": ["
            + "{\"quote\": \"We will enrol 182 adults\", \"page\": 1.5},"
            + "{\"quote\": \" We will enrol 182 adults \", \"page\": 1},"
            + "{\"quote\": \"randomised controlled trial of sardines\", \"page\": null},"
            + "{\"quote\": \"  \"}, {\"quote\": 7}, \"not an object\"],"
            + "\"reasoning\": 3, \"value\": \"182\", \"confidence\": \"high\"}");

        // A page that is not a whole number is as good as none
        assertEquals(List.of(new ExtractionReply.Quote("We will enrol 182 adults", null, "Population"),
            new ExtractionReply.Quote("randomised controlled trial of sardines", null, "Design")), reply.quotes());
        assertEquals(2, reply.quotesOffered());
        // A reasoning or a confidence of the wrong type is as good as none
        assertEquals("", reply.reasoning());
        assertEquals(0.0, reply.confidence());
    }

    @Test
    void readsNoEvidenceWhenThereIsNoListOfIt()
    {
        final ExtractionReply.FieldReply reply = readOne(field("k", false, List.of()),
            "{\"evidence\": \"none\", \"value\": \"x\"}");

        assertTrue(reply.quotes().isEmpty());
        assertEquals(0, reply.quotesOffered());
    }

    @Test
    void turnsTypedValuesIntoText()
    {
        assertEquals(List.of("182"), readValue("182").values());
        assertEquals(List.of("2.5"), readValue("2.50").values());
        assertEquals(List.of("true"), readValue("true").values());
        assertEquals(List.of("false"), readValue("false").values());
        assertEquals(List.of("a title"), readValue("\" a title \"").values());
    }

    @Test
    void findsNoAnswerInNullOrBlankOrSomethingElse()
    {
        for (final String nothing : List.of("null", "\"  \"", "{\"a\": 1}", "[]", "[null, \" \"]")) {
            final ExtractionReply.FieldReply reply = readValue(nothing);
            assertTrue(reply.values().isEmpty(), nothing);
            assertFalse(reply.isFound(), nothing);
        }
    }

    @Test
    void readsAListAsOneValuePerItem()
    {
        assertEquals(List.of("Toronto General", "Mount Sinai, Toronto", "3"),
            readValue("[\"Toronto General\", \"Mount Sinai, Toronto\", 3]").values());
    }

    @Test
    void matchesAValueToTheOptionItNames()
    {
        final ExtractionField choice = field("kind", true, DRUG_OR_DEVICE);

        assertEquals(List.of("drug", "drug", "device", "a vaccine"), readOne(choice,
            "{\"value\": [\"drug\", \"DRUG\", \"medical device\", \"a vaccine\"]}").values());
    }

    @Test
    void answersALeftOutQuestionWithNothing()
    {
        final Map<String, ExtractionReply.FieldReply> replies = ExtractionReply.read(
            List.of(field("asked", false, List.of()), field("forgotten", false, List.of()), field("odd", false,
                List.of())), "{\"asked\": {\"value\": \"x\"}, \"odd\": \"not an object\"}", DOCUMENT);

        assertEquals(List.of("asked", "forgotten", "odd"), List.copyOf(replies.keySet()));
        assertEquals(List.of("x"), replies.get("asked").values());
        assertEquals(new ExtractionReply.FieldReply("forgotten", List.of(), "", 0.0, List.of(), 0),
            replies.get("forgotten"));
        assertFalse(replies.get("odd").isFound());
    }

    @Test
    void holdsTheConfidenceBetweenZeroAndOne()
    {
        assertEquals(1.0, readOne(field("k", false, List.of()), "{\"confidence\": 7}").confidence());
        assertEquals(0.0, readOne(field("k", false, List.of()), "{\"confidence\": -1}").confidence());
    }

    @Test
    void findsTheObjectWhereverTheModelPutIt()
    {
        final Map<String, ExtractionReply.FieldReply> replies = ExtractionReply.read(List.of(field("k", false,
            List.of())), "Here you go:\n```json\n{\"k\": {\"value\": \"x\"}}\n```", DOCUMENT);

        assertEquals(List.of("x"), replies.get("k").values());
    }

    @Test
    void readsNothingOutOfAReplyWithNoObject()
    {
        final List<ExtractionField> fields = List.of(field("k", false, List.of()));
        for (final String reply : new String[] { null, "", "no JSON here", "} backwards {", "{\"k\": [1, }" }) {
            assertNull(ExtractionReply.read(fields, reply, DOCUMENT), String.valueOf(reply));
        }
    }

    @Test
    void saysWhyAReplyCannotBeRead()
    {
        assertEquals("it was empty", ExtractionReply.describeUnreadable(null));
        assertEquals("it was empty", ExtractionReply.describeUnreadable(" \n"));
        assertEquals("it was cut off before the end", ExtractionReply.describeUnreadable("{\"k\": {\"value\": \"x"));
        assertEquals("it held no JSON object", ExtractionReply.describeUnreadable("I cannot answer that."));
        assertEquals("it held no JSON object", ExtractionReply.describeUnreadable("{\"k\": [1, }"));
        assertNull(ExtractionReply.describeUnreadable("{\"k\": {}}"));
    }
}
