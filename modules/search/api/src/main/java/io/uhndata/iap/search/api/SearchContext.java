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
package io.uhndata.iap.search.api;

import java.util.List;

import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;

/**
 * What a {@link io.uhndata.iap.search.spi.QuickSearchEngine quick search engine} is asked for. It holds the text to
 * look for, how many matches are wanted, which node types to look in, and on whose behalf. Build one with
 * {@link SearchContextFactory}.
 *
 * <p>
 * A context belongs to the one search it was built for and must not outlive it. The
 * {@link #getResourceResolver() resource resolver} it carries is the requesting user's, and closes with their request.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface SearchContext
{
    /**
     * The text to look for, as the user typed it. It is not escaped. An engine binds it into its query, or escapes
     * it with {@link SearchUtils}.
     *
     * @return a string that is not blank
     */
    @NotNull
    String getQuery();

    /**
     * How many results the caller can use. An engine bounds its own query to this many, and the caller reads no more.
     *
     * @return a strictly positive number
     */
    long getMaxResults();

    /**
     * The node types to look in. They are always among the types the engine
     * {@link io.uhndata.iap.search.spi.QuickSearchEngine#getSupportedTypes() supports}. An engine returns results of
     * no other type.
     *
     * @return node type names, such as {@code sub:Submission}; never empty
     */
    @NotNull
    List<String> getResourceTypes();

    /**
     * The resource resolver of the user the search is run for. An engine reads through it and nothing else, so it
     * returns only what that user may read.
     *
     * @return the requesting user's resource resolver, open for the duration of the search
     */
    @NotNull
    ResourceResolver getResourceResolver();
}
