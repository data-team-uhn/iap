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

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

/**
 * Builder for {@link SearchParameters} instances. Start with {@link #newSearchParameters()}, configure, and
 * {@link #build()}; the builder may be reused afterwards, since {@code build()} takes a copy of the configuration.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class SearchParametersFactory
{
    /** The number of results asked for when the caller doesn't specify one: {@value}. */
    public static final long DEFAULT_MAX_RESULTS = 10;

    private String query;

    private long maxResults = DEFAULT_MAX_RESULTS;

    private List<String> resourceTypes = List.of();

    /** Private constructor, the only way to instantiate this is through {@link #newSearchParameters()}. */
    private SearchParametersFactory()
    {
        // Private, the only way to instantiate this is through newSearchParameters()
    }

    /**
     * Start building a new {@link SearchParameters} instance.
     *
     * @return a factory instance
     */
    @NotNull
    public static SearchParametersFactory newSearchParameters()
    {
        return new SearchParametersFactory();
    }

    /**
     * Set the {@link SearchParameters#getQuery() query} to look for.
     *
     * @param query the text to look for
     * @return this builder, for chaining calls
     */
    @NotNull
    public SearchParametersFactory withQuery(final String query)
    {
        this.query = query;
        return this;
    }

    /**
     * Set the {@link SearchParameters#getMaxResults() maximum number of results} wanted. If not specified,
     * {@value #DEFAULT_MAX_RESULTS} is used.
     *
     * @param maxResults the maximum number of results to return, a strictly positive number
     * @return this builder, for chaining calls
     * @throws IllegalArgumentException if {@code maxResults} is not strictly positive
     */
    @NotNull
    public SearchParametersFactory withMaxResults(final long maxResults)
    {
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be > 0");
        }
        this.maxResults = maxResults;
        return this;
    }

    /**
     * Set the {@link SearchParameters#getResourceTypes() node types} to look in.
     *
     * @param resourceTypes the node types to search, in the {@code sub:Submission} format
     * @return this builder, for chaining calls
     */
    @NotNull
    public SearchParametersFactory withResourceTypes(final List<String> resourceTypes)
    {
        this.resourceTypes = resourceTypes == null ? List.of() : List.copyOf(resourceTypes);
        return this;
    }

    /**
     * Build a {@link SearchParameters} instance as configured so far. The factory instance can continue to be
     * configured, but further changes will not affect the returned {@code SearchParameters} instance.
     *
     * @return a {@link SearchParameters} instance
     * @throws IllegalStateException if the query or the node types to search have not been set yet
     */
    @NotNull
    public SearchParameters build()
    {
        if (StringUtils.isBlank(this.query)) {
            throw new IllegalStateException("Query not set yet, withQuery(query) must be called before build()");
        }
        if (this.resourceTypes.isEmpty()) {
            throw new IllegalStateException(
                "Node types not set yet, withResourceTypes(types) must be called before build()");
        }
        return new SearchParametersImpl(this.query, this.maxResults, this.resourceTypes);
    }

    /**
     * The immutable snapshot handed to the search engines.
     *
     * @since 0.1.0
     */
    private static final class SearchParametersImpl implements SearchParameters
    {
        private final String query;

        private final long maxResults;

        private final List<String> resourceTypes;

        SearchParametersImpl(final String query, final long maxResults, final List<String> resourceTypes)
        {
            this.query = query;
            this.maxResults = maxResults;
            this.resourceTypes = resourceTypes;
        }

        @Override
        @NotNull
        public String getQuery()
        {
            return this.query;
        }

        @Override
        public long getMaxResults()
        {
            return this.maxResults;
        }

        @Override
        @NotNull
        public List<String> getResourceTypes()
        {
            // Already immutable, but copyOf is what says so to the static analysis; it returns the same instance
            return List.copyOf(this.resourceTypes);
        }
    }
}
