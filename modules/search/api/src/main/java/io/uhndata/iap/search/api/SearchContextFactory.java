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
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Builder for {@link SearchContext} instances. Start with {@link #newSearchContext()}, configure, then
 * {@link #build()}. The builder may be reused afterwards: {@code build()} copies the configuration.
 *
 * <p>
 * Reuse it within one request, not across several. A built context carries the requesting user's resource resolver,
 * which is closed with that request.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class SearchContextFactory
{
    /** The number of results asked for when the caller does not specify one: {@value}. */
    public static final long DEFAULT_MAX_RESULTS = 10;

    private String query;

    private long maxResults = DEFAULT_MAX_RESULTS;

    private List<String> resourceTypes = List.of();

    private ResourceResolver resourceResolver;

    /** Private constructor, instances come from {@link #newSearchContext()}. */
    private SearchContextFactory()
    {
        // Use newSearchContext()
    }

    /**
     * Start building a new {@link SearchContext} instance.
     *
     * @return a factory instance
     */
    @NotNull
    public static SearchContextFactory newSearchContext()
    {
        return new SearchContextFactory();
    }

    /**
     * Set the {@link SearchContext#getQuery() query} to look for.
     *
     * @param query the text to look for
     * @return this builder, for chaining calls
     */
    @NotNull
    public SearchContextFactory withQuery(final String query)
    {
        this.query = query;
        return this;
    }

    /**
     * Set the {@link SearchContext#getMaxResults() maximum number of results} wanted. If not specified,
     * {@value #DEFAULT_MAX_RESULTS} is used.
     *
     * @param maxResults the maximum number of results to return, a strictly positive number
     * @return this builder, for chaining calls
     * @throws IllegalArgumentException if {@code maxResults} is not strictly positive
     */
    @NotNull
    public SearchContextFactory withMaxResults(final long maxResults)
    {
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be > 0");
        }
        this.maxResults = maxResults;
        return this;
    }

    /**
     * Set the {@link SearchContext#getResourceTypes() node types} to look in.
     *
     * @param resourceTypes the node types to search, in the {@code sub:Submission} format
     * @return this builder, for chaining calls
     */
    @NotNull
    public SearchContextFactory withResourceTypes(final List<String> resourceTypes)
    {
        this.resourceTypes = resourceTypes == null ? List.of() : List.copyOf(resourceTypes);
        return this;
    }

    /**
     * Set the {@link SearchContext#getResourceResolver() resource resolver} the search reads through.
     *
     * @param resourceResolver the requesting user's resource resolver
     * @return this builder, for chaining calls
     */
    @NotNull
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
        justification = "A resource resolver is a live handle on the requesting user's session; sharing the caller's "
            + "own instance is the point of carrying it, and there is nothing to copy")
    public SearchContextFactory withResourceResolver(final ResourceResolver resourceResolver)
    {
        this.resourceResolver = resourceResolver;
        return this;
    }

    /**
     * Builds a {@link SearchContext} as configured so far. Further configuration of this builder does not affect the
     * returned context.
     *
     * @return a {@link SearchContext} instance
     * @throws IllegalStateException if the query, the node types to search, or the resource resolver have not been
     *             set yet
     */
    @NotNull
    public SearchContext build()
    {
        if (StringUtils.isBlank(this.query)) {
            throw new IllegalStateException("Query not set yet, withQuery(query) must be called before build()");
        }
        if (this.resourceTypes.isEmpty()) {
            throw new IllegalStateException(
                "Node types not set yet, withResourceTypes(types) must be called before build()");
        }
        // Mandatory rather than defaulted. A search with no resolver cannot be access-controlled.
        if (this.resourceResolver == null) {
            throw new IllegalStateException(
                "Resource resolver not set yet, withResourceResolver(resolver) must be called before build()");
        }
        return new SearchContextImpl(this.query, this.maxResults, this.resourceTypes, this.resourceResolver);
    }

    /**
     * The immutable snapshot handed to the search engines.
     *
     * @since 0.1.0
     */
    private static final class SearchContextImpl implements SearchContext
    {
        private final String query;

        private final long maxResults;

        private final List<String> resourceTypes;

        private final ResourceResolver resourceResolver;

        SearchContextImpl(final String query, final long maxResults, final List<String> resourceTypes,
            final ResourceResolver resourceResolver)
        {
            this.query = query;
            this.maxResults = maxResults;
            this.resourceTypes = resourceTypes;
            this.resourceResolver = resourceResolver;
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
            // The list is already immutable. copyOf returns the same instance, and satisfies the static analysis.
            return List.copyOf(this.resourceTypes);
        }

        @Override
        @NotNull
        @SuppressFBWarnings(value = "EI_EXPOSE_REP",
            justification = "A resource resolver is a live handle on the requesting user's session; handing an engine"
                + " the caller's own instance is the point of carrying it, and there is nothing to copy")
        public ResourceResolver getResourceResolver()
        {
            return this.resourceResolver;
        }
    }
}
