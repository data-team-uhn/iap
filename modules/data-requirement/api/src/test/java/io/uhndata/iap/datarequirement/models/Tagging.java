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
package io.uhndata.iap.datarequirement.models;

import java.util.Set;
import java.util.function.Function;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.tags.models.Taggable;

/**
 * The tags bundle's view, as these tests need it: a resource reads its own {@code tags} property as the tags it
 * carries.
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
     * Makes resources in this context adapt to a {@code Taggable} reading their own {@code tags} property.
     * Without it, {@code as(Taggable.class)} answers {@code null}, as it does without the tags bundle.
     *
     * @param context the test's Sling context
     */
    static void enable(final SlingContext context)
    {
        context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
            final Taggable taggable = Mockito.mock(Taggable.class);
            Mockito.when(taggable.hasOwnTag(Mockito.anyString())).thenAnswer(invocation ->
                Set.of(resource.getValueMap().get("tags", new String[0])).contains(invocation.getArgument(0)));
            return taggable;
        });
    }
}
