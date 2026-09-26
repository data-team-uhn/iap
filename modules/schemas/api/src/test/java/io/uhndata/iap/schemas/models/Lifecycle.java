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
package io.uhndata.iap.schemas.models;

import java.util.Map;
import java.util.function.Function;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.tags.models.Taggable;

/**
 * Places lifecycle tags in a mock repository. The tags service does not run under sling-mock, so the inheritance
 * of {@code retired} is reproduced here from the paths it was placed on.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Lifecycle
{
    private Lifecycle()
    {
        // Utility class
    }

    /**
     * Makes the Taggable view report the given tags.
     *
     * @param context the mock context
     * @param tagged the tag placed on each path
     */
    static void tag(final SlingContext context, final Map<String, String> tagged)
    {
        context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
            final String path = resource.getPath();
            final Taggable taggable = Mockito.mock(Taggable.class);
            tagged.forEach((taggedPath, tag) -> {
                if (path.equals(taggedPath)) {
                    Mockito.when(taggable.hasOwnTag(tag)).thenReturn(true);
                }
                if (path.equals(taggedPath) || "retired".equals(tag) && path.startsWith(taggedPath + "/")) {
                    Mockito.when(taggable.hasTag(tag)).thenReturn(true);
                }
            });
            return taggable;
        });
    }
}
