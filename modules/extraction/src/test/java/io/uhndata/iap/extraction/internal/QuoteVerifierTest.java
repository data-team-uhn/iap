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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link QuoteVerifier}: when a quote counts as really being in the document.
 *
 * <p>The tests go through {@link DocumentScan}, the only way the real code uses the verifier, so the tests see
 * the document prepared the same way a reading does.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
class QuoteVerifierTest
{
    private static final String TEXT = "<!-- page: 3 -->\n## Aims\n\nThe primary aim is to"
        + " reduce\treadmissions   within 30 days.\n";

    /** Whether the quote is found anywhere in the prepared document, the way a reading checks it. */
    private static boolean isVerified(final String quote, final String text)
    {
        return DocumentScan.of(text).locate(quote) >= 0;
    }

    @Test
    void findsAQuoteThatIsThere()
    {
        assertTrue(isVerified("The primary aim is to reduce readmissions", TEXT));
    }

    @Test
    void ignoresCaseAndTheShapeOfWhitespace()
    {
        assertTrue(isVerified("the PRIMARY aim   is to\nreduce readmissions", TEXT));
    }

    @Test
    void ignoresPageMarkersOnEitherSide()
    {
        assertTrue(isVerified("<!-- page: 3 --> Aims The primary aim", TEXT));
        assertTrue(isVerified("## Aims The primary", TEXT));
    }

    // A correctly copied quote that runs across a paragraph break must still be found
    @Test
    void findsAQuoteThatReachesAcrossAParagraphBreak()
    {
        assertTrue(isVerified("Aims The primary aim is to reduce readmissions", TEXT));
    }

    // A quote past the fuzzy limit only gets an exact check, so it must still match across a paragraph break
    @Test
    void findsALongQuoteThatReachesAcrossAParagraphBreak()
    {
        final String opening = "In this study the investigators set out to establish whether the intervention "
            + "changes the rate at which participants return to hospital after being discharged, and they say "
            + "as much at some length before the aims themselves are stated in the section below this one. ";
        final String document = "# Protocol\n\n" + opening.repeat(5) + "\n\n## Aims\n\nThe primary aim.\n";
        final String quote = opening.repeat(5).strip() + " ## Aims The primary aim.";

        assertTrue(quote.length() > 1000, "past the length that is only ever checked for containment");
        assertTrue(isVerified(quote, document));
    }

    @Test
    void refusesAQuoteThatIsNotThere()
    {
        assertFalse(isVerified("The secondary aim is to increase readmissions", TEXT));
    }

    @Test
    void refusesAQuoteTooShortToMeanAnything()
    {
        assertFalse(isVerified("ai", TEXT), "two letters match almost any text, though these are there");
        assertFalse(isVerified("  ", TEXT));
        assertTrue(isVerified("aim", TEXT), "three letters are enough");
    }

    @Test
    void refusesWhenThereIsNothingToCompare()
    {
        assertFalse(isVerified(null, TEXT));
        assertEquals(-1, DocumentScan.of(null).locate("The primary aim"));
    }

    // A model that drops a word has still pointed at the right passage. One that writes a plausible
    // sentence that is not there has not.
    @Test
    void forgivesADroppedWord()
    {
        assertTrue(isVerified("The primary aim is reduce readmissions within 30 days", TEXT));
    }

    // A number changes what the passage says, not just how it is worded
    @Test
    void refusesADroppedOrChangedNumber()
    {
        assertFalse(isVerified("The primary aim is to reduce readmissions within days", TEXT));
        assertFalse(isVerified("The primary aim is to reduce readmissions within 60 days", TEXT));
    }

    @Test
    void refusesAnAddedNegation()
    {
        assertFalse(isVerified("The primary aim is not to reduce readmissions within 30 days", TEXT));
    }

    // Missing text costs as much as added text, so two sentences cannot be joined into one quote
    @Test
    void refusesAQuoteStitchedFromTwoSentences()
    {
        final String document = "Participants will be compensated for their time with a gift card. The study"
            + " will enrol fifty participants at three sites over a period of eighteen months.";

        assertTrue(isVerified("Participants will be compensated for their time with a gift card.", document));
        assertFalse(isVerified(
            "Participants will be compensated for their time with a gift card over a period of eighteen months",
            document));
    }

    @Test
    void forgivesAChangedPunctuationMark()
    {
        assertTrue(isVerified("The primary aim is to reduce readmissions, within 30 days", TEXT));
    }

    @Test
    void refusesASentenceThatWasInvented()
    {
        assertFalse(isVerified(
            "Participants were randomised to receive the study drug or placebo", TEXT));
    }

    // Being partly there is not enough: a quote far from anything in the document is not evidence
    @Test
    void refusesAQuoteOnlyPartlyThere()
    {
        assertFalse(isVerified("The primary aim is to reduce hospital admissions greatly", TEXT));
    }

    // Real protocols are long. A quote with one word off must still be found in a document of that size.
    @Test
    void forgivesADroppedWordInADocumentOfARealisticLength()
    {
        final String protocol = "# Protocol\n\n"
            + "Filler sentence about the study design. ".repeat(500)
            + "\n\n## Aims\n\nThe primary aim is to reduce readmissions within 30 days.\n\n"
            + "More filler about statistics and monitoring. ".repeat(500);

        assertTrue(protocol.length() > 20_000, "longer than the old give-up threshold, as any protocol is");
        assertTrue(isVerified("The primary aim is reduce readmissions within 30 days", protocol));
    }

    @Test
    void stillRefusesAnInventedSentenceInADocumentOfThatLength()
    {
        final String protocol = "Filler sentence about the study design. ".repeat(1000);

        assertFalse(isVerified("Participants were randomised to receive the study drug or placebo", protocol));
    }

    // Past this length, a passage that is not there word for word was put together, not copied. A fuzzy check
    // would also cost more than it is worth.
    @Test
    void checksAVeryLongQuoteForExactContainmentOnly()
    {
        final String sentence = "The study will enrol participants across three sites. ";
        final String document = sentence.repeat(100);
        final String tooLong = sentence.repeat(20) + "and one invented clause.";

        assertTrue(tooLong.length() > 1000);
        assertFalse(isVerified(tooLong, document));
        assertTrue(isVerified(sentence.repeat(20), document), "the same passage verbatim still holds");
    }

    // An opening that repeats many times must not use up every candidate place, or the place the middle of the
    // quote points to is never compared
    @Test
    void findsAQuoteWhoseOpeningRepeatsThroughTheDocument()
    {
        final String heading = "## Statistical considerations\n\nThe analysis is described below.\n\n";
        final String document = heading.repeat(6)
            + "## Statistical considerations\n\nThe analysis is described below and uses a mixed model.\n";

        // "used" for "uses": not there word for word, so only the middle anchor can find it
        assertTrue(isVerified("The analysis is described below and used a mixed model.", document));
    }
}
