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

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.search.api.SearchContext;
import io.uhndata.iap.search.api.SearchUtils;

/**
 * Searches for one kind of content on behalf of the {@code quick} mode of the {@code /search} endpoint. Every
 * registered engine that supports one of the requested node types is called, in no particular order, until enough
 * results have been collected.
 *
 * <p>
 * A quick search answers while the user is still typing. An engine looks wherever the user would expect a match,
 * including in descendants of the content it returns. It describes each match with
 * {@link SearchUtils#addMatchMetadata}, which the client uses to show why a result is there.
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
     * Finds content matching the search. An implementation matches the query in the properties of the content it
     * knows, or in those of its descendants. It reads through the context's
     * {@link SearchContext#getResourceResolver() resource resolver}, which keeps the matches to what the requesting
     * user may see.
     *
     * @param context what to look for, how much of it is wanted, and on whose behalf
     * @return the matches, possibly {@link Results#empty() none}
     */
    @NotNull
    Results quickSearch(@NotNull SearchContext context);

    /**
     * The matches found by an engine. Each is serialized only when it is asked for. A caller stops as soon as it has
     * enough results, and the matches it never reaches are never serialized.
     *
     * @since 0.1.0
     */
    interface Results extends Iterator<JsonObject>, AutoCloseable
    {
        /**
         * Discards the next match without serializing it. The caller uses this for the matches it counts but does
         * not return, such as those before the requested offset. Make it cheaper than {@link #next()} where
         * possible.
         */
        default void skip()
        {
            next();
        }

        /**
         * Releases whatever the search held, typically a session or a resource resolver the engine opened. Called
         * exactly once, whether the matches were read to the end or not.
         *
         * <p>
         * Narrowed from {@link AutoCloseable#close()} to throw nothing. A caller can do nothing about a failure to
         * release, and the response is usually already going out by then.
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
