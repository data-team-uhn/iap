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
package io.uhndata.iap.submissions.models;

import java.util.Set;
import java.util.function.Function;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.tags.models.Taggable;

/**
 * Makes the {@code Taggable} view available in a mock repository. The tags service the model delegates to does not
 * run under sling-mock, so the one thing these models ask of it — whether a tag is placed on a node itself — is
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
     * installed. Without this, {@code as(Taggable.class)} answers {@code null} and a model reading tags reports
     * none, which is the degradation these models are also expected to survive.
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
            return taggable;
        });
    }
}
