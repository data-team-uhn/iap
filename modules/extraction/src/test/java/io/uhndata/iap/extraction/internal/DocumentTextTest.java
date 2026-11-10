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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DocumentText}: reading the parsed text of a file, and finding the heading above a quote.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DocumentTextTest
{
    private static final String PATH = "/Submissions/aa/proposal/v1/file/markdownFile";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private Resource createFileWithText(final String text)
    {
        final Resource file = this.context.create().resource(PATH, Map.of("jcr:primaryType", "nt:file"));
        this.context.create().resource(file.getPath() + "/jcr:content", Map.of(
            "jcr:primaryType", "nt:resource",
            "jcr:data", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
        return file;
    }

    @Test
    void readsTheTextOfAStoredFile() throws IOException
    {
        assertEquals("# Aims\n\nTo find out.\n", DocumentText.readText(createFileWithText("# Aims\n\nTo find out.\n")));
    }

    // No parsed file gives an empty text, not an error. The caller decides what that means.
    @Test
    void readsNothingWhenThereIsNoFile() throws IOException
    {
        assertEquals("", DocumentText.readText(null));
    }

    @Test
    void readsNothingWhenTheFileHasNoContent() throws IOException
    {
        final Resource file = this.context.create().resource(PATH, Map.of("jcr:primaryType", "nt:file"));

        assertEquals("", DocumentText.readText(file));
    }

    @Test
    void readsNothingWhenTheContentHasNoBytes() throws IOException
    {
        final Resource file = this.context.create().resource(PATH, Map.of("jcr:primaryType", "nt:file"));
        this.context.create().resource(file.getPath() + "/jcr:content", Map.of("jcr:primaryType", "nt:resource"));

        assertEquals("", DocumentText.readText(file));
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
    // and that position is used to find the heading.
    @Test
    void findsTheHeadingOfAQuoteThatOnlyMatchedFuzzily()
    {
        final DocumentScan scan =
            DocumentScan.of("## Objectives\n\nThe aim is to find out what happens next.\n");

        assertEquals("Objectives", getHeadingOver(scan, "The aim is to find out what happened next."));
    }

    @Test
    void findsTheHeadingOfAQuoteOnTheLastLine()
    {
        final DocumentScan scan = DocumentScan.of("## Objectives\nThe aim is to find out.");

        assertEquals("Objectives", getHeadingOver(scan, "The aim is to find out."));
    }

    @Test
    void namesNoHeadingForAQuoteThatIsNotThere()
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
    void namesNoHeadingForNothingToLookIn()
    {
        assertEquals(-1, DocumentScan.of(null).locate("anything"));
        assertEquals(-1, DocumentScan.of("## Objectives\n\nSomething.\n").locate(null));
        assertEquals(-1, DocumentScan.of("## Objectives\n\nSomething.\n").locate("   "));
        assertEquals("", DocumentScan.of("").getHeading(-1));
    }

    /**
     * Finds the quote, then returns the heading above it, the same way the intake does.
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
