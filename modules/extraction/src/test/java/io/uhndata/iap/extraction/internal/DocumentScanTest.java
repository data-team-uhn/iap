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

/**
 * Unit tests for {@link DocumentScan}: several documents joined into one text, and telling which one a quote
 * came from.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DocumentScanTest
{
    private static final DocumentScan JOINED = DocumentScan.joined(List.of("Preamble", "Questionnaire"),
        List.of("This survey asks about your care.\n\n## Why\n\nTo improve it.", "1. How was your visit?"));

    @Test
    void joinsTheDocumentsEachUnderItsHeading()
    {
        assertEquals("# Preamble\n\nThis survey asks about your care.\n\n## Why\n\nTo improve it.\n\n"
            + "# Questionnaire\n\n1. How was your visit?", JOINED.getText());
    }

    // A heading inside a document is still that document: the part is decided by where each one starts, not
    // by the nearest heading
    @Test
    void tellsWhichDocumentAQuoteCameFrom()
    {
        assertEquals(0, JOINED.partAt(JOINED.locate("This survey asks about your care.")));
        assertEquals(0, JOINED.partAt(JOINED.locate("To improve it.")));
        assertEquals(1, JOINED.partAt(JOINED.locate("How was your visit?")));
    }

    @Test
    void placesNothingThatWasNotFound()
    {
        assertEquals(-1, JOINED.partAt(-1));
    }

    @Test
    void hasNoPartsWhenOneDocumentIsReadAlone()
    {
        final DocumentScan single = DocumentScan.of("This survey asks about your care.");

        assertEquals(-1, single.partAt(single.locate("This survey asks about your care.")));
    }
}
