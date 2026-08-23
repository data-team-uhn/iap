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
package io.uhndata.iap.tags.models;

import java.util.Set;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link Taggable} guards; the actual tag operations are covered through
 * {@code TagManagerImplTest}, with the backing service registered.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TaggableTest
{
    private final SlingContext context = new SlingContext();

    private Taggable taggable;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Taggable.class);
        // No TagOperations service is registered in this context, e.g. a read-only rendering context
        this.taggable = this.context.create()
            .resource("/data/entity", "sling:resourceType", "data/Entity")
            .adaptTo(Taggable.class);
    }

    @Test
    void anyContentIsViewableAsTaggable()
    {
        // The iap:Taggable mixin is a declaration aid, not an adaptation gate: any content adapts
        assertNotNull(this.context.create().resource("/data/plain").adaptTo(Taggable.class));
    }

    @Test
    void readsAreEmptyWithoutTheTagsService()
    {
        assertTrue(this.taggable.getTags().isEmpty());
        assertTrue(this.taggable.getEffectiveTags().isEmpty());
        assertTrue(this.taggable.getEffectiveTagNames().isEmpty());
        assertTrue(this.taggable.getApplicableDefinitions().isEmpty());
        assertFalse(this.taggable.hasTag("draft"));
        assertFalse(this.taggable.hasOwnTag("draft"));
    }

    @Test
    void writesFailLoudlyWithoutTheTagsService()
    {
        assertThrows(IllegalStateException.class, () -> this.taggable.tag("draft"));
        assertThrows(IllegalStateException.class, () -> this.taggable.untag("draft"));
        assertThrows(IllegalStateException.class, () -> this.taggable.setTags(Set.of("draft")));
        assertEquals("entity", this.taggable.getName());
    }
}
