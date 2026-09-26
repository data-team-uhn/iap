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
 * Unit tests for {@link QuoteVerifier}: what counts as a quote really being in the text.
 *
 * <p>Driven through {@link DocumentScan}, which is the only door production uses. Asking the verifier directly
 * meant comparing against a document normalized one way while a reading normalizes it another, so the two could
 * disagree about what the same text was and nothing here would notice.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
class QuoteVerifierTest
{
    private static final String TEXT = "<!-- page: 3 -->\n## Aims\n\nThe primary aim is to"
        + " reduce\treadmissions   within 30 days.\n";

    /** Whether a quote holds up, the way a reading asks it: is it anywhere in the prepared document. */
    private static boolean verifies(final String quote, final String text)
    {
        return DocumentScan.of(text).locate(quote) >= 0;
    }

    @Test
    void findsAQuoteThatIsThere()
    {
        assertTrue(verifies("The primary aim is to reduce readmissions", TEXT));
    }

    @Test
    void ignoresCaseAndTheShapeOfWhitespace()
    {
        assertTrue(verifies("the PRIMARY aim   is to\nreduce readmissions", TEXT));
    }

    @Test
    void ignoresPageMarkersOnEitherSide()
    {
        assertTrue(verifies("<!-- page: 3 --> Aims The primary aim", TEXT));
        assertTrue(verifies("## Aims The primary", TEXT));
    }

    // The one that mattered. A blank line used to put a second space in the document but not in the quote, so a
    // quote reaching across a paragraph break was never found however faithfully it had been copied.
    @Test
    void findsAQuoteThatReachesAcrossAParagraphBreak()
    {
        assertTrue(verifies("Aims The primary aim is to reduce readmissions", TEXT));
    }

    // Worse for a long one: past the fuzzy limit only exact containment is tried, so the extra space was the
    // whole of the answer and the quote was dropped outright
    @Test
    void findsALongQuoteThatReachesAcrossAParagraphBreak()
    {
        final String opening = "In this study the investigators set out to establish whether the intervention "
            + "changes the rate at which participants return to hospital after being discharged, and they say "
            + "as much at some length before the aims themselves are stated in the section below this one. ";
        final String document = "# Protocol\n\n" + opening.repeat(5) + "\n\n## Aims\n\nThe primary aim.\n";
        final String quote = opening.repeat(5).strip() + " ## Aims The primary aim.";

        assertTrue(quote.length() > 1000, "past the length that is only ever checked for containment");
        assertTrue(verifies(quote, document));
    }

    @Test
    void refusesAQuoteThatIsNotThere()
    {
        assertFalse(verifies("The secondary aim is to increase readmissions", TEXT));
    }

    @Test
    void refusesAQuoteTooShortToMeanAnything()
    {
        assertFalse(verifies("aim", TEXT), "three letters match almost any text");
        assertFalse(verifies("  ", TEXT));
    }

    @Test
    void refusesWhenThereIsNothingToCompare()
    {
        assertFalse(verifies(null, TEXT));
        assertEquals(-1, DocumentScan.of(null).locate("The primary aim"));
    }

    // A model that drops a word has still pointed at the right passage. One that writes a plausible
    // sentence that is not there has not, and that is the line the floor draws.
    @Test
    void forgivesADroppedWord()
    {
        assertTrue(verifies("The primary aim is to reduce readmissions within days", TEXT));
    }

    @Test
    void forgivesAChangedPunctuationMark()
    {
        assertTrue(verifies("The primary aim is to reduce readmissions, within 30 days", TEXT));
    }

    @Test
    void refusesASentenceThatWasInvented()
    {
        assertFalse(verifies(
            "Participants were randomised to receive the study drug or placebo", TEXT));
    }

    // Partly there is not enough: a quote a long way from anything in the document is not evidence for it
    @Test
    void refusesAQuoteOnlyPartlyThere()
    {
        assertFalse(verifies("The primary aim is to reduce hospital admissions greatly", TEXT));
    }

    // The comparison used to be given up on for any text past a few thousand characters, which is every real
    // protocol: a quote a word out of true was dropped as evidence and halved the answer confidence with it.
    @Test
    void forgivesADroppedWordInADocumentOfARealisticLength()
    {
        final String protocol = "# Protocol\n\n"
            + "Filler sentence about the study design. ".repeat(500)
            + "\n\n## Aims\n\nThe primary aim is to reduce readmissions within 30 days.\n\n"
            + "More filler about statistics and monitoring. ".repeat(500);

        assertTrue(protocol.length() > 20_000, "longer than the old give-up threshold, as any protocol is");
        assertTrue(verifies("The primary aim is to reduce readmissions within days", protocol));
    }

    @Test
    void stillRefusesAnInventedSentenceInADocumentOfThatLength()
    {
        final String protocol = "Filler sentence about the study design. ".repeat(1000);

        assertFalse(verifies("Participants were randomised to receive the study drug or placebo", protocol));
    }

    // Past this length a passage that is not there verbatim was built rather than copied, and comparing it
    // properly costs more than the answer is worth
    @Test
    void checksAVeryLongQuoteForExactContainmentOnly()
    {
        final String sentence = "The study will enrol participants across three sites. ";
        final String document = sentence.repeat(100);
        final String tooLong = sentence.repeat(20) + "and one invented clause.";

        assertTrue(tooLong.length() > 1000);
        assertFalse(verifies(tooLong, document));
        assertTrue(verifies(sentence.repeat(20), document), "the same passage verbatim still holds");
    }

    // An opening that repeats through a protocol must not use up every candidate place, or the passage the
    // middle of the quote would have found is never compared
    @Test
    void findsAQuoteWhoseOpeningRepeatsThroughTheDocument()
    {
        final String heading = "## Statistical considerations\n\nThe analysis is described below.\n\n";
        final String document = heading.repeat(6)
            + "## Statistical considerations\n\nThe analysis is described below and uses a mixed model.\n";

        assertTrue(verifies("The analysis is described below and uses a mixed model.", document));
    }
}
