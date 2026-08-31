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

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import jakarta.json.JsonObject;

import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.search.api.SearchParameters;
import io.uhndata.iap.search.api.SearchUtils;

/**
 * Searches for one kind of content on behalf of the {@code quick} mode of the {@code /search} endpoint. Every engine
 * registered as a service is asked about the node types it can search, and those that can serve the request are
 * called, in no particular order, until enough results have been collected.
 *
 * <p>
 * A quick search is meant to answer "what do I have that mentions this?" while the user is still typing, so an engine
 * is expected to look wherever the user would expect a match to be found — including in descendants of the content
 * it returns — and to describe each match with {@link SearchUtils#addMatchMetadata} so that the client can show the
 * user why the result is there.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface QuickSearchEngine
{
    /**
     * The node types this engine can search.
     *
     * @return a list of JCR node types, usually a singleton, in the {@code sub:Submission} format
     */
    @NotNull
    List<String> getSupportedTypes();

    /**
     * Check whether the given node type is one this engine can search.
     *
     * @param type the JCR node type to check, in the {@code sub:Submission} format
     * @return {@code true} if the node type is supported, {@code false} otherwise
     */
    default boolean isTypeSupported(@NotNull final String type)
    {
        return getSupportedTypes().contains(type);
    }

    /**
     * Finds content matching the given query. Implementations match the query as appropriate for the content they
     * know, either directly in its properties, or in the properties of its descendants.
     *
     * @param query what to look for
     * @param resourceResolver the resource resolver of the user making the request, so that the results are the ones
     *            that user is allowed to see
     * @return the matches, possibly {@link Results#empty() none}
     */
    @NotNull
    Results quickSearch(@NotNull SearchParameters query, @NotNull ResourceResolver resourceResolver);

    /**
     * The matches found by an engine, serialized one at a time so that the ones that don't make it into the response
     * are never serialized at all.
     *
     * <p>
     * The caller stops as soon as it has enough results, which for any search with more matches than fit on a page is
     * what normally happens, so an implementation is told when it may let go of whatever it opened rather than being
     * left to guess from a last {@link #next()} that never comes.
     * </p>
     *
     * @since 0.1.0
     */
    interface Results extends Iterator<JsonObject>, AutoCloseable
    {
        /**
         * Discards the next match without serializing it. This is what the caller uses for the results it has to
         * count but not return, e.g. those before the requested offset, so an implementation should make it cheaper
         * than {@link #next()} whenever it can.
         */
        default void skip()
        {
            next();
        }

        /**
         * Releases whatever was held for this search: a resource resolver or a session the engine opened to run it,
         * typically. Called exactly once, whether the results were read to the end or not.
         *
         * <p>
         * Narrowed from {@link AutoCloseable#close()} so that it throws nothing: releasing what a search held is not
         * something a caller can do anything about, and the response is usually already on its way out by then.
         * </p>
         */
        @Override
        default void close()
        {
            // Nothing to release by default
        }

        /**
         * No matches at all.
         *
         * @return an empty result set
         */
        @NotNull
        static Results empty()
        {
            return new Results()
            {
                @Override
                public boolean hasNext()
                {
                    return false;
                }

                @Override
                public JsonObject next()
                {
                    throw new NoSuchElementException();
                }

                @Override
                public void skip()
                {
                    // Nothing to skip
                }
            };
        }
    }
}
