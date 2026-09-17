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
 * @version $Id$
 * @since 0.1.0
 */
class QuoteVerifierTest
{
    private static final String TEXT = "<!-- page: 3 -->\n[chunk:Chunk-2]\n## Aims\n\nThe primary aim is to"
        + " reduce\treadmissions   within 30 days.\n";

    @Test
    void findsAQuoteThatIsThere()
    {
        assertTrue(QuoteVerifier.isVerbatim("The primary aim is to reduce readmissions", TEXT));
    }

    @Test
    void ignoresCaseAndTheShapeOfWhitespace()
    {
        assertTrue(QuoteVerifier.isVerbatim("the PRIMARY aim   is to\nreduce readmissions", TEXT));
    }

    @Test
    void ignoresPageAndChunkMarkersOnEitherSide()
    {
        assertTrue(QuoteVerifier.isVerbatim("<!-- page: 3 --> Aims The primary aim", TEXT));
        assertTrue(QuoteVerifier.isVerbatim("[chunk:Chunk-2] ## Aims The primary", TEXT));
    }

    @Test
    void refusesAQuoteThatIsNotThere()
    {
        assertFalse(QuoteVerifier.isVerbatim("The secondary aim is to increase readmissions", TEXT));
    }

    @Test
    void refusesAQuoteTooShortToMeanAnything()
    {
        assertFalse(QuoteVerifier.isVerbatim("aim", TEXT), "three letters match almost any text");
        assertFalse(QuoteVerifier.isVerbatim("  ", TEXT));
    }

    @Test
    void refusesWhenThereIsNothingToCompare()
    {
        assertFalse(QuoteVerifier.isVerbatim(null, TEXT));
        assertFalse(QuoteVerifier.isVerbatim("The primary aim", null));
    }

    // A model that drops a word has still pointed at the right passage. One that writes a plausible
    // sentence that is not there has not, and that is the line the floor draws.
    @Test
    void forgivesADroppedWord()
    {
        assertTrue(QuoteVerifier.isVerbatim("The primary aim is to reduce readmissions within days", TEXT));
    }

    @Test
    void forgivesAChangedPunctuationMark()
    {
        assertTrue(QuoteVerifier.isVerbatim("The primary aim is to reduce readmissions, within 30 days", TEXT));
    }

    @Test
    void refusesASentenceThatWasInvented()
    {
        assertFalse(QuoteVerifier.isVerbatim(
            "Participants were randomised to receive the study drug or placebo", TEXT));
    }

    @Test
    void scoresAPerfectCopyAsWhole()
    {
        assertEquals(1.0, QuoteVerifier.score("The primary aim is to reduce readmissions", TEXT));
    }

    @Test
    void scoresNothingWhenThereIsNothingToCompare()
    {
        assertEquals(0.0, QuoteVerifier.score(null, TEXT));
        assertEquals(0.0, QuoteVerifier.score("aim", TEXT));
    }

    // Partly there is worth saying, because it is what separates a slip from an invention
    @Test
    void scoresAPartialMatchInBetween()
    {
        final double score = QuoteVerifier.score("The primary aim is to reduce hospital admissions greatly", TEXT);

        assertTrue(score > 0.0 && score < 1.0, "partly there, and the number says how much: " + score);
    }
}
