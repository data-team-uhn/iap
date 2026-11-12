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
package io.uhndata.iap.links.models;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;

/**
 * Shared setup for the tests that read link definitions: the {@code /libs/link} resource type registry the mock
 * repository does not load.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class LinkTypesFixture
{
    private LinkTypesFixture()
    {
        // Utility class, not to be instantiated
    }

    /**
     * Declares the resource type hierarchy that lets a definition of either kind answer to the shared
     * {@code link/Definition} type. It ships as {@code /libs} content, which the mock repository does not load.
     *
     * @param context the Sling context to register the types in
     */
    public static void registerDefinitionTypes(final SlingContext context)
    {
        context.create().resource("/libs/link/InternalDefinition",
            "sling:resourceSuperType", LinkDefinition.RESOURCE_TYPE);
        context.create().resource("/libs/link/ExternalDefinition",
            "sling:resourceSuperType", LinkDefinition.RESOURCE_TYPE);
    }
}
