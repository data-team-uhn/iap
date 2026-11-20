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

import java.util.Arrays;
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;

import io.uhndata.iap.tags.testing.PropertyTagging;

/**
 * Retires categories in a mock repository. In Oak, an inheritable tag placed on one node is copied onto its whole
 * subtree at commit time. Here the {@code retired} tag is placed on the given categories and declared inheritable, so
 * the categories under them carry it too.
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
     * Retires the given categories and everything under them.
     *
     * @param context the mock context to install the tags in
     * @param paths the paths of existing categories that carry the {@code retired} tag themselves
     */
    static void retire(final SlingContext context, final String... paths)
    {
        PropertyTagging.builder().inheritable(Category.RETIRED_TAG).installIn(context);
        Arrays.stream(paths)
            .map(path -> Objects.requireNonNull(context.resourceResolver().getResource(path)))
            .forEach(category -> Objects.requireNonNull(category.adaptTo(ModifiableValueMap.class))
                .put("tags", new String[] {Category.RETIRED_TAG}));
    }
}
