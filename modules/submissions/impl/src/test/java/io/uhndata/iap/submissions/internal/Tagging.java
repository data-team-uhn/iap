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

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.tags.models.Taggable;

/**
 * Makes the {@code Taggable} view available in a mock repository. The tags service the models delegate to does not
 * run under sling-mock, so the one thing the handlers ask of it — whether a tag is placed on a node itself — is
 * answered here from the node's own {@code tags} property, which is what the service reads for real.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Tagging
{
    private Tagging()
    {
        // Utility class
    }

    /**
     * Registers the {@code Taggable} view, so that content adapts to it the way it does with the tags bundle
     * installed. Without this, a submission reports no tags at all, and a handler asking whether it is still a
     * draft is told it is not.
     *
     * @param context the mock context whose resources become taggable
     */
    static void enable(final SlingContext context)
    {
        context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
            final Set<String> own = Set.of(resource.getValueMap().get("tags", new String[0]));
            final Taggable taggable = Mockito.mock(Taggable.class);
            Mockito.when(taggable.hasOwnTag(Mockito.anyString()))
                .thenAnswer(invocation -> own.contains(invocation.getArgument(0)));
            // Placing and removing write back to the node's own tags, so a test reads the outcome where the real
            // service would have left it rather than by interrogating a mock
            try {
                Mockito.when(taggable.tag(Mockito.anyString(), Mockito.anyBoolean()))
                    .thenAnswer(invocation -> write(resource, own, invocation.getArgument(0), true));
                Mockito.when(taggable.untag(Mockito.anyString(), Mockito.anyBoolean()))
                    .thenAnswer(invocation -> write(resource, own, invocation.getArgument(0), false));
            } catch (final PersistenceException e) {
                // Declared by the methods being stubbed, thrown by neither the stubbing nor the mock
                throw new IllegalStateException(e);
            }
            return taggable;
        });
    }

    /**
     * Adds or removes one tag on a node.
     *
     * @param resource the node to change
     * @param own the tags it carried when it was adapted
     * @param name the tag to place or remove
     * @param placing {@code true} to place it, {@code false} to remove it
     * @return whether the node's tags changed
     */
    private static boolean write(final Resource resource, final Set<String> own, final String name,
        final boolean placing)
    {
        final Set<String> tags = new LinkedHashSet<>(own);
        final boolean changed = placing ? tags.add(name) : tags.remove(name);
        if (changed) {
            Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class),
                "A mock resource is always modifiable").put("tags", tags.toArray(String[]::new));
        }
        return changed;
    }
}
