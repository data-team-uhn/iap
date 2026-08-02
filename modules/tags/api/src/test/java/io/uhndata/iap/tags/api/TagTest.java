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
package io.uhndata.iap.tags.api;

import java.util.Set;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.tags.models.TagDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link Tag} value object.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TagTest
{
    private final SlingContext context = new SlingContext();

    @Test
    void exposesItsComponents()
    {
        this.context.addModelsForClasses(Content.class, TagDefinition.class);
        final TagDefinition definition = this.context.create().resource("/Tags/draft",
            "sling:resourceType", "iap/TagDefinition").adaptTo(TagDefinition.class);

        final Tag tag = new Tag("draft", definition, Set.of(Tag.Origin.EXPLICIT), Set.of("/data/entity"));

        assertEquals("draft", tag.getName());
        assertSame(definition, tag.getDefinition());
        assertTrue(tag.isDefined());
        assertEquals(Set.of(Tag.Origin.EXPLICIT), tag.getOrigins());
        assertEquals(Set.of("/data/entity"), tag.getSources());
        assertEquals("draft[EXPLICIT]", tag.toString());
    }

    @Test
    void toleratesMissingDefinitionAndOrigins()
    {
        final Tag tag = new Tag("legacy", null, Set.of(), Set.of());

        assertFalse(tag.isDefined());
        assertTrue(tag.getOrigins().isEmpty());
        assertTrue(tag.getSources().isEmpty());
    }

    @Test
    void comparesByName()
    {
        final Tag tag = new Tag("draft", null, Set.of(Tag.Origin.EXPLICIT), Set.of("/data/entity"));
        final Tag sameName = new Tag("draft", null, Set.of(Tag.Origin.INHERITED), Set.of("/data"));
        final Tag otherName = new Tag("submitted", null, Set.of(Tag.Origin.EXPLICIT), Set.of("/data/entity"));

        assertEquals(tag, tag);
        assertEquals(tag, sameName);
        assertEquals(tag.hashCode(), sameName.hashCode());
        assertNotEquals(tag, otherName);
        assertNotEquals(tag, "draft");
    }
}
