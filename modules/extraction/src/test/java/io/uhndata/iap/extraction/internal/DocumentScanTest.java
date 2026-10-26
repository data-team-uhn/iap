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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DocumentScan}: placing a quote under its heading, and in the document it came from.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DocumentScanTest
{
    private static final String PROTOCOL = "Untitled opening words of the protocol.\n\n# Protocol\n\n"
        + "## Design\n\nA randomised controlled trial.\n\n### Arms\n\nSardines against a usual diet.\n\n"
        + "#Not a heading without its space.\n";

    @Test
    void placesAQuoteUnderTheNearestHeadingAboveIt()
    {
        final DocumentScan scan = DocumentScan.of(PROTOCOL);

        assertEquals("Design", scan.getHeading(scan.locate("A randomised controlled trial.")));
        assertEquals("Arms", scan.getHeading(scan.locate("Not a heading without its space.")));
        assertEquals("", scan.getHeading(scan.locate("Untitled opening words of the protocol.")));
        assertEquals("", scan.getHeading(-1));
    }

    @Test
    void keepsTheTextAsItWasParsed()
    {
        final DocumentScan scan = DocumentScan.of(PROTOCOL);

        assertEquals(PROTOCOL, scan.getText());
        assertFalse(scan.isBlank());
        assertTrue(DocumentScan.of(" \n ").isBlank());
    }

    @Test
    void scansNothingForNoText()
    {
        assertSame(DocumentScan.EMPTY, DocumentScan.of(null));
        assertSame(DocumentScan.EMPTY, DocumentScan.of(""));
        assertTrue(DocumentScan.EMPTY.isBlank());
    }

    @Test
    void tellsWhichOfSeveralJoinedDocumentsAQuoteCameFrom()
    {
        final DocumentScan joined = DocumentScan.join(List.of("Protocol", "Consent form"),
            List.of("The study enrols adults.", "You may stop taking part at any time."));

        assertEquals("# Protocol\n\nThe study enrols adults.\n\n# Consent form\n\n"
            + "You may stop taking part at any time.", joined.getText());
        assertEquals(0, joined.getPart(joined.locate("The study enrols adults.")));
        assertEquals(1, joined.getPart(joined.locate("You may stop taking part at any time.")));
        assertEquals("Consent form", joined.getHeading(joined.locate("You may stop taking part")));
        assertEquals(-1, joined.getPart(-1));
        assertEquals(-1, DocumentScan.of(PROTOCOL).getPart(0));
    }

    @Test
    void refusesAQuoteRunningFromOneJoinedDocumentIntoTheNext()
    {
        final DocumentScan joined = DocumentScan.join(List.of("Protocol", "Consent form"),
            List.of("The study enrols adults.", "You may stop taking part at any time."));

        assertEquals(-1, joined.locate("The study enrols adults. Consent form You may stop"));
    }
}
