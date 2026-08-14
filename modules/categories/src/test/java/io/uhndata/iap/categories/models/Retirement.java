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
package io.uhndata.iap.categories.models;

import java.util.Set;
import java.util.function.Function;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.tags.models.Taggable;

/**
 * Retires categories in a mock repository. The tags service the {@code Taggable} model delegates to does not run
 * under sling-mock, so the effect an inheritable tag has for real - placed on one node, materialized onto its whole
 * subtree at commit time - is reproduced here from the paths it was placed on.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Retirement
{
    private Retirement()
    {
        // Utility class
    }

    /**
     * Marks the given categories retired, and every category below them with them.
     *
     * @param context the mock context whose resources are being tagged
     * @param paths the paths the {@code retired} tag is placed on
     */
    static void retire(final SlingContext context, final String... paths)
    {
        final Set<String> tagged = Set.of(paths);
        context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
            final String path = resource.getPath();
            final Taggable taggable = Mockito.mock(Taggable.class);
            Mockito.when(taggable.hasOwnTag(Category.RETIRED_TAG)).thenReturn(tagged.contains(path));
            Mockito.when(taggable.hasTag(Category.RETIRED_TAG)).thenReturn(
                tagged.stream().anyMatch(marked -> path.equals(marked) || path.startsWith(marked + "/")));
            return taggable;
        });
    }
}
