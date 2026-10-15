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
import java.util.List;
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
 * run under sling-mock. What the handlers ask of it is whether a tag is placed on a node itself, and to place one.
 * Both are answered here from the node's own {@code tags} property, which is what the service uses for real.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class Tagging
{
    private Tagging()
    {
        // Utility class
    }

    /**
     * Registers the {@code Taggable} view, so that content adapts to it the way it does with the tags bundle
     * installed. Without this, {@code as(Taggable.class)} answers {@code null} and a model reading tags reports
     * none.
     *
     * @param context the mock context whose resources become taggable
     */
    public static void enable(final SlingContext context)
    {
        context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
            final Taggable taggable = Mockito.mock(Taggable.class);
            try {
                stub(taggable, resource);
            } catch (final PersistenceException e) {
                // Stubbing names the method rather than calling it, so this cannot happen
                throw new IllegalStateException(e);
            }
            return taggable;
        });
    }

    /**
     * Teaches the mock to answer from, and write to, the resource's own {@code tags} property.
     *
     * <p>Placing and removing are stubbed as well as reading, because handlers place and retire a submission's
     * tags: without it, the test fails on the absent tags service rather than on anything it is about.</p>
     *
     * @param taggable the mock standing in for the view
     * @param resource the resource it stands for
     * @throws PersistenceException never; {@code tag} declares it and this only names the method
     */
    private static void stub(final Taggable taggable, final Resource resource) throws PersistenceException
    {
        Mockito.when(taggable.hasOwnTag(Mockito.anyString())).thenAnswer(invocation ->
            Set.of(resource.getValueMap().get("tags", new String[0])).contains(invocation.getArgument(0)));
        Mockito.when(taggable.tag(Mockito.anyString())).thenAnswer(invocation ->
            write(resource, invocation.getArgument(0), true));
        Mockito.when(taggable.tag(Mockito.anyString(), Mockito.anyBoolean())).thenAnswer(invocation ->
            write(resource, invocation.getArgument(0), true));
        Mockito.when(taggable.untag(Mockito.anyString())).thenAnswer(invocation ->
            write(resource, invocation.getArgument(0), false));
        Mockito.when(taggable.untag(Mockito.anyString(), Mockito.anyBoolean())).thenAnswer(invocation ->
            write(resource, invocation.getArgument(0), false));
    }

    /**
     * Places or removes one tag on a node, reading the tags it carries now rather than when it was adapted.
     *
     * @param resource the node to change
     * @param name the tag to place or remove
     * @param placing {@code true} to place it, {@code false} to remove it
     * @return whether the node's tags changed
     */
    private static boolean write(final Resource resource, final String name, final boolean placing)
    {
        final ModifiableValueMap properties = Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class));
        final Set<String> names = new LinkedHashSet<>(List.of(properties.get("tags", new String[0])));
        final boolean changed = placing ? names.add(name) : names.remove(name);
        if (changed) {
            properties.put("tags", names.toArray(new String[0]));
        }
        return changed;
    }
}
