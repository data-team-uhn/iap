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
package io.uhndata.iap.submissions.models;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Chunk}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ChunkTest
{
    private static final String CHUNK_PATH = "/Submissions/submission/consent/v0/file/chunks/chunk001";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Chunk.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(CHUNK_PATH,
            "sling:resourceType", Chunk.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Chunk.class));
    }

    @Test
    void isIdentifiedByItsNodeName()
    {
        final Resource resource = this.context.create().resource(CHUNK_PATH,
            "sling:resourceType", Chunk.RESOURCE_TYPE);
        final Chunk chunk = resource.adaptTo(Chunk.class);

        // The catalog's chunk_id is not stored as a property; the node's name is it
        assertEquals("chunk001", chunk.getName());
    }

    @Test
    void exposesWhatTheChunkerAndTheTaggerRecorded()
    {
        final Resource resource = this.context.create().resource(CHUNK_PATH, Map.of(
            "sling:resourceType", Chunk.RESOURCE_TYPE,
            "summary", "How participants are found and consented",
            "rubricTags", new String[]{ "recruitment", "consent" },
            "tagBasis", "fulltext",
            "uncertain", false,
            "pageStart", 7L,
            "pageEnd", 9L));
        final Chunk chunk = resource.adaptTo(Chunk.class);

        assertEquals("How participants are found and consented", chunk.getSummary());
        assertEquals(List.of("recruitment", "consent"), chunk.getRubricTags());
        assertEquals("fulltext", chunk.getTagBasis());
        assertFalse(chunk.isUncertain());
        assertEquals(7L, chunk.getPageStart());
        assertEquals(9L, chunk.getPageEnd());
    }

    @Test
    void marksTagsGuessedFromTheHeadingsAsUncertain()
    {
        final Resource resource = this.context.create().resource(CHUNK_PATH, Map.of(
            "sling:resourceType", Chunk.RESOURCE_TYPE,
            "rubricTags", new String[]{ "recruitment" },
            "tagBasis", "heading",
            "uncertain", true));
        final Chunk chunk = resource.adaptTo(Chunk.class);

        // Only "fulltext" and "deep" count as content-based
        assertEquals("heading", chunk.getTagBasis());
        assertTrue(chunk.isUncertain());
    }

    @Test
    void exposesItsMarkdown()
    {
        final Resource resource = this.context.create().resource(CHUNK_PATH,
            "sling:resourceType", Chunk.RESOURCE_TYPE);
        this.context.create().resource(CHUNK_PATH + "/content", "sling:resourceType", "nt:file");
        final Chunk chunk = resource.adaptTo(Chunk.class);

        assertEquals("content", chunk.getContent().getName());
    }

    @Test
    void toleratesAChunkNothingHasReadYet()
    {
        final Resource resource = this.context.create().resource(CHUNK_PATH,
            "sling:resourceType", Chunk.RESOURCE_TYPE);
        final Chunk chunk = resource.adaptTo(Chunk.class);

        assertNotNull(chunk);
        assertNull(chunk.getSummary());
        assertTrue(chunk.getRubricTags().isEmpty());
        assertNull(chunk.getTagBasis());
        assertFalse(chunk.isUncertain());
        // Absent for documents that came in as DOCX, which carry no page markers
        assertNull(chunk.getPageStart());
        assertNull(chunk.getPageEnd());
        assertNull(chunk.getContent());
    }
}
