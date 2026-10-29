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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.models.WorkflowFixture;

/**
 * Tagging in a mock repository, for the tag service task tests. The tags service the {@code Taggable} model
 * delegates to does not run under sling-mock, so the view reads and writes the node's own {@code tags} property,
 * which is what the service does for real, and refuses the one undefined tag the tests use. Which tags a
 * replacement retires is the tags service's own rule, so a replacement is only recorded here.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TaggingFixture
{
    /** A tag that has no definition, so placing it is refused. */
    static final String UNDEFINED = "undefined";

    private final List<String> replacements = new ArrayList<>();

    private TaggingFixture()
    {
    }

    /**
     * Registers the {@code Taggable} view over the node's own {@code tags} property.
     *
     * @param context the mock context whose resources become taggable
     * @return the fixture, which remembers the replacements asked of it
     */
    static TaggingFixture enable(final SlingContext context)
    {
        final TaggingFixture fixture = new TaggingFixture();
        context.registerAdapter(Resource.class, Taggable.class,
            (Function<Resource, Taggable>) fixture::taggableFor);
        return fixture;
    }

    /**
     * The tags placed in place of others, in the order they were asked for.
     *
     * @return the tag names given to {@code tagReplacing}
     */
    List<String> getReplacements()
    {
        return this.replacements;
    }

    private Taggable taggableFor(final Resource resource)
    {
        final Taggable taggable = Mockito.mock(Taggable.class);
        try {
            Mockito.when(taggable.getTags()).thenAnswer(invocation -> WorkflowFixture.tags(resource));
            Mockito.when(taggable.tag(Mockito.anyString(), Mockito.eq(true))).thenAnswer(invocation -> {
                final String tag = invocation.getArgument(0);
                refuseUndefined(tag);
                final Set<String> tags = WorkflowFixture.tags(resource);
                final boolean added = tags.add(tag);
                if (added) {
                    WorkflowFixture.writeTags(resource, tags);
                }
                return added;
            });
            Mockito.when(taggable.tagReplacing(Mockito.anyString(), Mockito.eq(true))).thenAnswer(invocation -> {
                final String tag = invocation.getArgument(0);
                refuseUndefined(tag);
                this.replacements.add(tag);
                return true;
            });
            Mockito.when(taggable.untag(Mockito.anyString(), Mockito.eq(true))).thenAnswer(invocation -> {
                final Set<String> tags = WorkflowFixture.tags(resource);
                final boolean removed = tags.remove(invocation.getArgument(0));
                if (removed) {
                    WorkflowFixture.writeTags(resource, tags);
                }
                return removed;
            });
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        return taggable;
    }

    private static void refuseUndefined(final String tag)
    {
        if (UNDEFINED.equals(tag)) {
            throw new IllegalArgumentException("Undefined tag: " + tag);
        }
    }
}
