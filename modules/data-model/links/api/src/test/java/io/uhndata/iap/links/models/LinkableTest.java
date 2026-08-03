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
package io.uhndata.iap.links.models;

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link Linkable} guards; the actual link operations are covered through
 * {@code LinkManagerImplTest}, with the backing service registered.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LinkableTest
{
    private final SlingContext context = new SlingContext();

    private Linkable linkable;

    private Content destination;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Linkable.class);
        // No LinkOperations service is registered in this context, e.g. a read-only rendering context
        this.linkable = this.context.create()
            .resource("/Things/a", Map.of("jcr:uuid", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
            .adaptTo(Linkable.class);
        this.destination = this.context.create()
            .resource("/Things/b", Map.of("jcr:uuid", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
            .adaptTo(Content.class);
    }

    @Test
    void anyContentIsViewableAsLinkable()
    {
        // The iap:Linkable mixin is a declaration aid, not an adaptation gate: any content adapts
        final Resource plain = this.context.create().resource("/Things/plain");
        assertNotNull(plain.adaptTo(Linkable.class));
        assertNotNull(this.destination.as(Linkable.class));
    }

    @Test
    void readsAreEmptyWithoutTheLinksService()
    {
        assertTrue(this.linkable.getLinks().isEmpty());
        assertTrue(this.linkable.getLinks("references").isEmpty());
        assertTrue(this.linkable.getBacklinks().isEmpty());
        assertEquals(0, this.linkable.removeLinks(this.destination, "references", null));
    }

    @Test
    void writesFailLoudlyWithoutTheLinksService()
    {
        assertThrows(IllegalStateException.class,
            () -> this.linkable.addLink(this.destination, "references", null));
        assertThrows(IllegalStateException.class,
            () -> this.linkable.addExternalLink("ehrChart", "42", null));
    }
}
