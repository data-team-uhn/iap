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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DocumentScan}: joining several documents into one text, telling which one a quote came
 * from, and the heading a quote sits under.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DocumentScanTest
{
    private static final DocumentScan JOINED = DocumentScan.join(List.of("Preamble", "Questionnaire"),
        List.of("This survey asks about your care.\n\n## Why\n\nTo improve it.", "1. How was your visit?"));

    @Test
    void joinsTheDocumentsEachUnderItsHeading()
    {
        assertEquals("# Preamble\n\nThis survey asks about your care.\n\n## Why\n\nTo improve it.\n\n"
            + "# Questionnaire\n\n1. How was your visit?", JOINED.getText());
    }

    // A heading inside a document does not start a new part. Each part starts where its document starts.
    @Test
    void tellsWhichDocumentAQuoteCameFrom()
    {
        assertEquals(0, JOINED.getPart(JOINED.locate("This survey asks about your care.")));
        assertEquals(0, JOINED.getPart(JOINED.locate("To improve it.")));
        assertEquals(1, JOINED.getPart(JOINED.locate("How was your visit?")));
    }

    // The end of one document and the start of the next were never side by side, so no real quote spans both
    @Test
    void refusesAQuoteThatRunsAcrossTwoDocuments()
    {
        assertEquals(-1, JOINED.locate("To improve it. # Questionnaire 1. How was your visit?"));
    }

    @Test
    void refusesANearQuoteThatRunsAcrossTwoDocuments()
    {
        assertEquals(-1, JOINED.locate("To improve it. Questionnaire 1. How was your visit?"));
    }

    @Test
    void placesNothingThatWasNotFound()
    {
        assertEquals(-1, JOINED.getPart(-1));
    }

    @Test
    void hasNoPartsWhenOneDocumentIsReadAlone()
    {
        final DocumentScan single = DocumentScan.of("This survey asks about your care.");

        assertEquals(-1, single.getPart(single.locate("This survey asks about your care.")));
    }

    @Test
    void findsTheHeadingAQuoteSitsUnder()
    {
        final String markdown =
            "# Protocol\n\n## Objectives\n\nThe aim is to find out.\n\n## Budget\n\nMoney spent.\n";
        final DocumentScan scan = DocumentScan.of(markdown);

        assertEquals("Objectives", getHeadingOver(scan, "The aim is to find out."));
        assertEquals("Budget", getHeadingOver(scan, "Money spent."));
    }

    // The model rarely copies spaces and line breaks exactly, so the quote is matched the way the verifier does
    @Test
    void findsTheHeadingEvenWhenTheQuoteWasReflowed()
    {
        final DocumentScan scan = DocumentScan.of("## Objectives\n\nThe aim is\nto find out.\n");

        assertEquals("Objectives", getHeadingOver(scan, "The aim is to find out."));
    }

    // A quote with a changed word has no exact match. The fuzzy search still says where it found the quote,
    // and that position, not the start of the stretch it searched, finds the heading.
    @Test
    void findsTheHeadingOfAQuoteThatOnlyMatchedFuzzily()
    {
        final DocumentScan scan = DocumentScan.of(
            "## Background\n\nWhy we ask.\n\n## Objectives\n\nThe aim is to find out what happens next.\n");

        assertEquals("Objectives", getHeadingOver(scan, "The aim is to find out what happened next."));
    }

    @Test
    void findsTheHeadingOfAQuoteOnTheLastLine()
    {
        final DocumentScan scan = DocumentScan.of("## Background\nWhy we ask.\n## Objectives\nThe aim is to find out.");

        assertEquals("Objectives", getHeadingOver(scan, "The aim is to find out."));
    }

    @Test
    void findsNoPositionForAQuoteThatIsNotThere()
    {
        assertEquals(-1, DocumentScan.of("## Objectives\n\nSomething.\n").locate("Absent sentence."));
    }

    @Test
    void namesNoHeadingWhenNothingTitlesTheQuote()
    {
        final DocumentScan scan = DocumentScan.of("Just prose, no headings at all.\n");

        assertTrue(scan.locate("Just prose, no headings") >= 0, "the quote is there");
        assertEquals("", getHeadingOver(scan, "Just prose, no headings"));
    }

    @Test
    void findsNoPositionForNoQuote()
    {
        assertEquals(-1, DocumentScan.of("## Objectives\n\nSomething.\n").locate(null));
        assertEquals("", DocumentScan.of("").getHeading(-1));
    }

    /**
     * Finds the quote, then returns the heading above it.
     *
     * @param scan the document
     * @param quote what the model quoted
     * @return the heading above the quote, or an empty string when the quote is not found
     */
    private static String getHeadingOver(final DocumentScan scan, final String quote)
    {
        final int at = scan.locate(quote);
        return at < 0 ? "" : scan.getHeading(at);
    }
}
