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
package io.uhndata.iap.search.spi;

import java.io.IOException;
import java.util.Iterator;

import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.search.api.EntityListing;

/**
 * Finds the entities an {@link EntityListing} asks for by a faster route than a JCR query, such as an index. The
 * pagination servlet asks each registered backend, highest {@code service.ranking} first, whether it can serve a
 * listing, and runs a JCR query when none can.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface EntityListingBackend
{
    /**
     * Whether this backend can answer a listing completely: every condition, the full text search and the order.
     * A backend that can answer only part of a listing must refuse it, since its answer replaces the JCR query's.
     *
     * @param listing the entities asked for
     * @return {@code true} if {@link #find} would return exactly the entities the JCR query would
     */
    boolean canServe(@NotNull EntityListing listing);

    /**
     * Runs the search and returns the matching entities, in the listing's order. Only entities the resolver can
     * read are returned, so a listing never reveals an entity its reader could not open.
     *
     * @param listing the entities asked for, one this backend {@link #canServe can serve}
     * @param resolver the resolver of the person asking, which decides what they may read
     * @return the absolute paths of the matching entities, in order; reading them may still fail with an unchecked
     *         exception, as a lazily read result can
     * @throws IOException if the search cannot run
     */
    @NotNull
    Iterator<String> find(@NotNull EntityListing listing, @NotNull ResourceResolver resolver) throws IOException;
}
