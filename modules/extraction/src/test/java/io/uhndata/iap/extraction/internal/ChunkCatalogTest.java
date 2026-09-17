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
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link ChunkCatalog}: how the chunks are named for the model, and what a chunk that opens
 * mid-prose is called.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ChunkCatalogTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final List<Chunk> chunks = new ArrayList<>();

    private void addChunk(final String text)
    {
        final String name = "Chunk-" + (this.chunks.size() + 1);
        final Resource chunk = this.context.create().resource("/chunks/" + name,
            Map.of("sling:resourceType", Chunk.RESOURCE_TYPE));
        final Resource file = this.context.create().resource(chunk.getPath() + "/content",
            Map.of("jcr:primaryType", "nt:file"));
        this.context.create().resource(file.getPath() + "/jcr:content", Map.of(
            "jcr:primaryType", "nt:resource",
            "jcr:data", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
        final Chunk model = chunk.adaptTo(Chunk.class);
        assertNotNull(model);
        this.chunks.add(model);
    }

    private List<String> headingsOf(final int index) throws Exception
    {
        return ChunkCatalog.read(this.chunks).get(index).headings();
    }

    @Test
    void namesAChunkByTheHeadingsItHolds() throws Exception
    {
        addChunk("## Methods\n\nHow.\n\n### Recruitment\n\nWho.\n");

        final ChunkCatalog.Entry entry = ChunkCatalog.read(this.chunks).get(0);

        assertEquals("Chunk-1", entry.getName());
        assertEquals(List.of("Methods", "Recruitment"), entry.headings());
        assertEquals("Methods; Recruitment", entry.describeHeadings());
        assertEquals("## Methods\n\nHow.\n\n### Recruitment\n\nWho.\n", entry.text());
    }

    @Test
    void aContinuationInheritsTheLastHeadingBeforeIt() throws Exception
    {
        addChunk("## Methods\n\nHow.\n\n### Recruitment\n\nWho.\n");
        addChunk("continued from the previous chunk.\n");

        assertEquals(List.of("Recruitment"), headingsOf(1));
    }

    @Test
    void aContinuationThatReachesItsOwnHeadingIsNamedByBoth() throws Exception
    {
        addChunk("## Methods\n\nHow.\n");
        addChunk("continued.\n\n## Analysis\n\nWhat.\n");

        assertEquals(List.of("Methods", "Analysis"), headingsOf(1));
    }

    @Test
    void aRunOfHeadlessChunksAllInheritFromTheSameChunk() throws Exception
    {
        addChunk("## Methods\n\nHow.\n");
        addChunk("still going.\n");
        addChunk("and going.\n");

        assertEquals(List.of("Methods"), headingsOf(1));
        assertEquals(List.of("Methods"), headingsOf(2));
    }

    @Test
    void aFirstChunkWithNoHeadingHasNothingToInherit() throws Exception
    {
        addChunk("a cover page with no heading.\n");
        addChunk("## Aims\n\nWhy.\n");

        assertEquals(List.of(), headingsOf(0));
        assertEquals("", ChunkCatalog.read(this.chunks).get(0).describeHeadings());
        assertEquals(List.of("Aims"), headingsOf(1));
    }
}
