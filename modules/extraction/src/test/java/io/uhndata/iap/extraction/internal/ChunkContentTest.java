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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.models.Chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ChunkContent}: reading a chunk back, and the two things about it that are worked out
 * rather than stored.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ChunkContentTest
{
    private static final String TEXT = "# Background\n\nWhy the study is being done.\n";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private Chunk chunkWith(final String name, final String text) throws Exception
    {
        final Resource chunk = this.context.create().resource("/chunks/" + name,
            Map.of("sling:resourceType", Chunk.RESOURCE_TYPE));
        if (text != null) {
            final Resource file = this.context.create().resource(chunk.getPath() + "/content",
                Map.of("jcr:primaryType", "nt:file"));
            this.context.create().resource(file.getPath() + "/jcr:content", Map.of(
                "jcr:primaryType", "nt:resource",
                "jcr:data", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
        }
        final Chunk model = chunk.adaptTo(Chunk.class);
        assertNotNull(model);
        return model;
    }

    @Test
    void readsWhatAChunkHolds() throws Exception
    {
        assertEquals(TEXT, ChunkContent.readText(chunkWith("Chunk-1", TEXT)));
    }

    @Test
    void readsNothingFromAChunkThatHoldsNothing() throws Exception
    {
        assertEquals("", ChunkContent.readText(chunkWith("Chunk-2", null)));
        assertEquals("", ChunkContent.readText((Chunk) null));
        assertEquals("", ChunkContent.readText((Resource) null));
    }

    @Test
    void readsNothingFromAFileWithNoContentNode() throws Exception
    {
        final Resource file = this.context.create().resource("/loose",
            Map.of("jcr:primaryType", "nt:file"));

        assertEquals("", ChunkContent.readText(file));
    }

    @Test
    void readsNothingFromAContentNodeThatCarriesNoData() throws Exception
    {
        final Resource file = this.context.create().resource("/empty",
            Map.of("jcr:primaryType", "nt:file"));
        this.context.create().resource(file.getPath() + "/jcr:content",
            Map.of("jcr:primaryType", "nt:resource"));

        assertEquals("", ChunkContent.readText(file));
    }

    @Test
    void takesTheHeadingAChunkOpensWith()
    {
        assertEquals(List.of("Background"), ChunkContent.getHeadings(TEXT));
        assertEquals(List.of("3.1 Aims"), ChunkContent.getHeadings("### 3.1 Aims\n\nText.\n"));
        assertEquals(List.of("Deep"), ChunkContent.getHeadings("###### Deep\n"));
    }

    @Test
    void looksPastWhatSaysNothing()
    {
        assertEquals(List.of("Methods"),
            ChunkContent.getHeadings("\n\n<!-- page: 4 -->\n---\n## Methods\n\nHow.\n"));
        assertEquals(List.of("Methods"), ChunkContent.getHeadings("<!--   page:   12   -->\n***\n## Methods\n"));
    }

    @Test
    void collectsTheTwoTopmostLevelsAndLeavesDeeperOnesOut()
    {
        final String markdown = "## Background\n\nWhy.\n\n### Rationale\n\nBecause.\n\n"
            + "#### Prior work\n\nSee also.\n\n### Objectives\n\nWhat.\n";

        assertEquals(List.of("Background", "Rationale", "Objectives"), ChunkContent.getHeadings(markdown));
    }

    @Test
    void collectsEveryHeadingWhenOnlyOneLevelIsPresent()
    {
        final String markdown = "### First\n\nA.\n\n### Second\n\nB.\n";

        assertEquals(List.of("First", "Second"), ChunkContent.getHeadings(markdown));
    }

    @Test
    void findsAHeadingEvenWhenTheChunkOpensMidProse()
    {
        // getHeadings looks at the whole body; opensWithHeading is what tells a caller this chunk is a
        // continuation that still needs an earlier heading carried over in front of this one.
        assertEquals(List.of("Later"), ChunkContent.getHeadings("continued from the previous chunk.\n\n## Later\n"));
    }

    @Test
    void doesNotMistakeMalformedLinesForHeadings()
    {
        assertEquals(List.of(), ChunkContent.getHeadings("#NoSpace is not a heading\n"));
        assertEquals(List.of(), ChunkContent.getHeadings("####### Too many hashes\n"));
    }

    @Test
    void tellsWhetherAChunkOpensWithItsOwnHeading()
    {
        assertTrue(ChunkContent.opensWithHeading(TEXT));
        assertTrue(ChunkContent.opensWithHeading("\n\n<!-- page: 4 -->\n---\n## Methods\n\nHow.\n"));
        assertFalse(ChunkContent.opensWithHeading("continued from the previous chunk.\n\n## Later\n"));
        assertFalse(ChunkContent.opensWithHeading("#NoSpace is not a heading\n"));
        assertFalse(ChunkContent.opensWithHeading(null));
        assertFalse(ChunkContent.opensWithHeading(""));
        assertFalse(ChunkContent.opensWithHeading("\n\n<!-- page: 1 -->\n"));
    }

    @Test
    void hasNoHeadingForNothingAtAll()
    {
        assertEquals(List.of(), ChunkContent.getHeadings(null));
        assertEquals(List.of(), ChunkContent.getHeadings(""));
        assertEquals(List.of(), ChunkContent.getHeadings("\n\n<!-- page: 1 -->\n"));
    }

    @Test
    void sizesTextByHowMuchOfItThereIs()
    {
        assertEquals(0, ChunkContent.estimateTokens(null));
        assertEquals(0, ChunkContent.estimateTokens(""));
        assertEquals(1, ChunkContent.estimateTokens("abcd"));
        assertEquals(250, ChunkContent.estimateTokens("x".repeat(1000)));
    }

    @Test
    void namesTheHeadingAQuoteSitsUnder()
    {
        final String text = "## Background\n\nSomething earlier.\n\n## Aims\n\nThe primary aim is to reduce"
            + " readmissions.\n";

        assertEquals("Aims", ChunkContent.findHeadingAbove(text, "The primary aim is to reduce readmissions"));
        assertEquals("Background", ChunkContent.findHeadingAbove(text, "Something earlier"));
    }

    // The model rarely copies whitespace exactly, so the lookup compares the same way the check does
    @Test
    void findsTheHeadingEvenWhenTheQuoteWasReflowed()
    {
        final String text = "## Aims\n\nThe primary aim\nis to reduce   readmissions.\n";

        assertEquals("Aims", ChunkContent.findHeadingAbove(text, "the PRIMARY aim is to reduce readmissions"));
    }

    @Test
    void namesTheNearestHeadingAboveRatherThanTheFirst()
    {
        final String text = "# Protocol\n\n## Methods\n\n### Recruitment\n\nForty-two people take part.\n";

        assertEquals("Recruitment", ChunkContent.findHeadingAbove(text, "Forty-two people take part"));
    }

    @Test
    void namesTheHeadingTheQuoteIsOnWhenTheQuoteIsTheHeading()
    {
        assertEquals("Aims", ChunkContent.findHeadingAbove("## Aims\n\nSomething.\n", "Aims"));
    }

    @Test
    void namesNothingWhenNoHeadingSitsAboveTheQuote()
    {
        assertEquals("", ChunkContent.findHeadingAbove("Just prose, no headings at all.\n", "Just prose"));
    }

    @Test
    void namesNothingForAQuoteThatIsNotThere()
    {
        assertEquals("", ChunkContent.findHeadingAbove("## Aims\n\nSomething.\n", "Participants were randomised"));
    }

    @Test
    void namesNothingWhenThereIsNothingToLookIn()
    {
        assertEquals("", ChunkContent.findHeadingAbove(null, "anything"));
        assertEquals("", ChunkContent.findHeadingAbove("## Aims\n", null));
        assertEquals("", ChunkContent.findHeadingAbove("## Aims\n", "  "));
    }
}
