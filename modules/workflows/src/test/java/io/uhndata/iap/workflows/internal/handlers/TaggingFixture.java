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
package io.uhndata.iap.workflows.internal.handlers;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.models.WorkflowFixture;

/**
 * Tagging in a mock repository, for the tag service task tests. The tags service the {@code Taggable} model
 * delegates to does not run under sling-mock, so the view reads and writes the node's own {@code tags} property,
 * which is what the service does for real, and refuses the one undefined tag the tests use.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TaggingFixture
{
    /** A tag that has no definition, so placing it is refused. */
    static final String UNDEFINED = "undefined";

    private TaggingFixture()
    {
    }

    /**
     * Registers the {@code Taggable} view over the node's own {@code tags} property.
     *
     * @param context the mock context whose resources become taggable
     */
    static void enable(final SlingContext context)
    {
        enable(context, Map.of());
    }

    /**
     * Registers the {@code Taggable} view, knowing which categories each tag belongs to, so that a tag placed to
     * replace the others of its category retires them.
     *
     * @param context the mock context whose resources become taggable
     * @param categories each tag's categories, by tag name; a tag left out belongs to none
     */
    static void enable(final SlingContext context, final Map<String, List<String>> categories)
    {
        context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
            final Taggable taggable = Mockito.mock(Taggable.class);
            try {
                Mockito.when(taggable.getTags()).thenAnswer(invocation -> tags(resource));
                Mockito.when(taggable.tag(Mockito.anyString(), Mockito.eq(true))).thenAnswer(invocation -> {
                    final String tag = invocation.getArgument(0);
                    refuseUndefined(tag);
                    final Set<String> tags = tags(resource);
                    return tags.add(tag) && write(resource, tags);
                });
                Mockito.when(taggable.tagReplacing(Mockito.anyString(), Mockito.eq(true))).thenAnswer(invocation -> {
                    final String tag = invocation.getArgument(0);
                    refuseUndefined(tag);
                    final Set<String> before = tags(resource);
                    final Set<String> after = WorkflowFixture.replacing(before, tag, categories);
                    return !after.equals(before) && write(resource, after);
                });
                Mockito.when(taggable.untag(Mockito.anyString(), Mockito.eq(true))).thenAnswer(invocation -> {
                    final Set<String> tags = tags(resource);
                    return tags.remove(invocation.getArgument(0)) && write(resource, tags);
                });
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
            return taggable;
        });
    }

    private static void refuseUndefined(final String tag)
    {
        if (UNDEFINED.equals(tag)) {
            throw new IllegalArgumentException("Undefined tag: " + tag);
        }
    }

    /**
     * The tags placed on a resource.
     *
     * @param resource a resource
     * @return its own tags, in order
     */
    static Set<String> tags(final Resource resource)
    {
        return new LinkedHashSet<>(Arrays.asList(resource.getValueMap().get("tags", new String[0])));
    }

    private static boolean write(final Resource resource, final Set<String> tags)
    {
        resource.adaptTo(ModifiableValueMap.class).put("tags", tags.toArray(new String[0]));
        return true;
    }
}
