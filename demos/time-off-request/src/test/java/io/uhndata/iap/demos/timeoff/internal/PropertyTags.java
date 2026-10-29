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
package io.uhndata.iap.demos.timeoff.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.tags.models.Taggable;

/**
 * The tags service as these tests need it: every resource adapts to a {@code Taggable} reading and writing its own
 * {@code tags} property. Like the real one, a write stays in the session until somebody commits it. There is one view
 * per path, so a test can verify the calls its subject made.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class PropertyTags
{
    private static final String TAGS_PROPERTY = "tags";

    private final Map<String, Taggable> views = new HashMap<>();

    private final Set<String> refused = new HashSet<>();

    private PropertyTags()
    {
    }

    /**
     * Makes resources in this context adapt to the views.
     *
     * @param context the test's Sling context
     * @return the views, for a test to refuse writes on or verify
     */
    static PropertyTags installIn(final SlingContext context)
    {
        final PropertyTags tags = new PropertyTags();
        context.registerAdapter(Resource.class, Taggable.class,
            (Function<Resource, Taggable>) resource -> tags.views.computeIfAbsent(resource.getPath(),
                path -> tags.viewOf(resource)));
        return tags;
    }

    /**
     * Makes every write to a resource fail, as a refused one does.
     *
     * @param path the absolute path of the resource that refuses
     */
    void refuseWritesTo(final String path)
    {
        this.refused.add(path);
    }

    private Taggable viewOf(final Resource resource)
    {
        final Taggable taggable = Mockito.mock(Taggable.class);
        Mockito.when(taggable.hasOwnTag(Mockito.anyString())).thenAnswer(invocation ->
            Set.of(resource.getValueMap().get(TAGS_PROPERTY, new String[0])).contains(invocation.getArgument(0)));
        try {
            Mockito.when(taggable.tag(Mockito.anyString(), Mockito.anyBoolean())).thenAnswer(invocation ->
                this.rewrite(resource, tags -> tags.add(invocation.getArgument(0))));
            Mockito.when(taggable.untag(Mockito.anyString(), Mockito.anyBoolean())).thenAnswer(invocation ->
                this.rewrite(resource, tags -> tags.remove(invocation.getArgument(0))));
        } catch (final PersistenceException e) {
            // Declared by tag and untag, never thrown while stubbing them
            throw new IllegalStateException(e);
        }
        return taggable;
    }

    /**
     * Changes a resource's tags in its session.
     *
     * @param resource the resource whose tags change
     * @param change edits the set of tag names in place, and answers whether it altered the set
     * @return what the change answered; {@code false} without calling it if the resource cannot be modified
     * @throws PersistenceException when the resource refuses writes
     */
    private boolean rewrite(final Resource resource, final Predicate<Set<String>> change)
        throws PersistenceException
    {
        if (this.refused.contains(resource.getPath())) {
            throw new PersistenceException("Nothing may be written to " + resource.getPath());
        }
        final ModifiableValueMap properties = resource.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            return false;
        }
        final Set<String> tags = new LinkedHashSet<>(Set.of(properties.get(TAGS_PROPERTY, new String[0])));
        final boolean changed = change.test(tags);
        properties.put(TAGS_PROPERTY, tags.toArray(String[]::new));
        return changed;
    }
}
