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
package io.uhndata.iap.workflows.internal;

import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.entities.models.Entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link EntityRevision}. What it owns is which node gets marked and that the mark is different
 * every time; that a differing value is what makes the repository refuse a concurrent write is Oak's behaviour,
 * measured separately, and not something a mock repository can show.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EntityRevisionTest
{
    private static final String TYPE = "sling:resourceType";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    @Test
    void marksTheEntityTheChangeWasAimedAt()
    {
        final Resource entity = this.context.create().resource("/Things/one", Map.of(TYPE, Entity.RESOURCE_TYPE));

        EntityRevision.stamp(entity);

        assertNotNull(entity.getValueMap().get(EntityRevision.PROPERTY, String.class));
    }

    // The event usually names a part rather than the entity: an answer being saved, a task being completed
    @Test
    void marksTheEntityEnclosingWhateverWasAimedAt()
    {
        this.context.create().resource("/Things/one", Map.of(TYPE, Entity.RESOURCE_TYPE));
        final Resource part = this.context.create().resource("/Things/one/answers/first",
            Map.of(TYPE, "sub/Answer"));

        EntityRevision.stamp(part);

        final Resource entity = this.context.resourceResolver().getResource("/Things/one");
        assertNotNull(entity);
        assertNotNull(entity.getValueMap().get(EntityRevision.PROPERTY, String.class));
        assertNull(part.getValueMap().get(EntityRevision.PROPERTY, String.class));
    }

    // Nearest wins: a submission inside a submission would be marked at the level acted on
    @Test
    void marksTheNearestEnclosingEntityRatherThanTheOutermost()
    {
        this.context.create().resource("/Things/outer", Map.of(TYPE, Entity.RESOURCE_TYPE));
        final Resource inner = this.context.create().resource("/Things/outer/inner",
            Map.of(TYPE, Entity.RESOURCE_TYPE));

        EntityRevision.stamp(this.context.create().resource("/Things/outer/inner/part", Map.of(TYPE, "sub/Answer")));

        final Resource outer = this.context.resourceResolver().getResource("/Things/outer");
        assertNotNull(outer);
        assertNotNull(inner.getValueMap().get(EntityRevision.PROPERTY, String.class));
        assertNull(outer.getValueMap().get(EntityRevision.PROPERTY, String.class));
    }

    // A definition or a piece of configuration is not something two people race to change through the engine
    @Test
    void leavesAChangeThatIsNotAboutAnEntityAlone()
    {
        final Resource plain = this.context.create().resource("/Config/thing", Map.of(TYPE, "app/Configuration"));

        EntityRevision.stamp(plain);

        assertNull(plain.getValueMap().get(EntityRevision.PROPERTY, String.class));
    }

    // The whole point: two writers must never produce the same value, or the repository merges them silently
    @Test
    void marksItDifferentlyEveryTime()
    {
        final Resource entity = this.context.create().resource("/Things/one", Map.of(TYPE, Entity.RESOURCE_TYPE));

        EntityRevision.stamp(entity);
        final String first = entity.getValueMap().get(EntityRevision.PROPERTY, String.class);
        EntityRevision.stamp(entity);
        final String second = entity.getValueMap().get(EntityRevision.PROPERTY, String.class);

        assertNotEquals(first, second);
    }

    // A resource that cannot be written through is not this class's problem to report: the commit that follows
    // will fail on its own, with the repository's own reason
    @Test
    void saysNothingAboutAnEntityItCannotWriteTo()
    {
        final Resource unwritable = Mockito.mock(Resource.class);
        Mockito.when(unwritable.isResourceType(Entity.RESOURCE_TYPE)).thenReturn(true);
        Mockito.when(unwritable.adaptTo(ModifiableValueMap.class)).thenReturn(null);

        EntityRevision.stamp(unwritable);

        Mockito.verify(unwritable).adaptTo(ModifiableValueMap.class);
    }

    @Test
    void namesThePropertyTheNodeTypeDeclares()
    {
        assertEquals("revision", EntityRevision.PROPERTY);
    }
}
