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
package io.uhndata.iap.submissions.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalInt;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Counts the pages of an uploaded PDF, so one too long for the parser is refused on upload.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class PdfPageCounter
{
    private PdfPageCounter()
    {
        // Utility
    }

    /**
     * The number of pages in a PDF.
     *
     * @param content the PDF's bytes, which the caller closes
     * @return the page count, or empty when the content cannot be read as a PDF, including one locked with a
     *         password; the parser refuses those with its own reason
     */
    static OptionalInt countPages(final InputStream content)
    {
        // Buffered in a temporary file rather than the heap, since an upload may be tens of megabytes
        try (PDDocument document = PDDocument.load(content, MemoryUsageSetting.setupTempFileOnly())) {
            return OptionalInt.of(document.getNumberOfPages());
        } catch (final IOException e) {
            return OptionalInt.empty();
        }
    }
}
