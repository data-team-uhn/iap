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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Chunks}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ChunksTest
{
    private static final String CHUNKS_PATH = "/Submissions/submission/consent/v0/file/chunks";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Chunks.class, Chunk.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource(CHUNKS_PATH,
            "sling:resourceType", Chunks.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Chunks.class));
    }

    @Test
    void listsChunksInDocumentOrder()
    {
        final Resource resource = this.context.create().resource(CHUNKS_PATH,
            "sling:resourceType", Chunks.RESOURCE_TYPE);
        this.context.create().resource(CHUNKS_PATH + "/chunk001", "sling:resourceType", Chunk.RESOURCE_TYPE);
        this.context.create().resource(CHUNKS_PATH + "/chunk002", "sling:resourceType", Chunk.RESOURCE_TYPE);
        this.context.create().resource(CHUNKS_PATH + "/catalog.json", "sling:resourceType", "nt:file");
        final Chunks chunks = resource.adaptTo(Chunks.class);

        final List<Chunk> listed = chunks.getChunks();

        assertEquals(2, listed.size());
        // The catalog's chunk_id is the node's own name
        assertEquals("chunk001", listed.get(0).getName());
        assertEquals("chunk002", listed.get(1).getName());
    }

    @Test
    void listsNothingBeforeTheChunksAreWritten()
    {
        final Resource resource = this.context.create().resource(CHUNKS_PATH,
            "sling:resourceType", Chunks.RESOURCE_TYPE);
        final Chunks chunks = resource.adaptTo(Chunks.class);

        assertTrue(chunks.getChunks().isEmpty());
    }
}
