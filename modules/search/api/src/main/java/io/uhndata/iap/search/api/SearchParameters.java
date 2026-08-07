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

import org.jetbrains.annotations.NotNull;

/**
 * What a {@link io.uhndata.iap.search.spi.QuickSearchEngine quick search engine} is asked to look for. Instances are
 * built with {@link SearchParametersFactory}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface SearchParameters
{
    /**
     * The text to look for, as the user typed it. It is not escaped in any way, so an engine placing it in a query
     * must escape it itself; see {@link SearchUtils}.
     *
     * @return a non-empty string
     */
    @NotNull
    String getQuery();

    /**
     * How many results the caller can use. An engine should bound its own query accordingly, since results past this
     * many are discarded.
     *
     * @return a strictly positive number
     */
    long getMaxResults();

    /**
     * The node types to look in, in the {@code sub:Submission} format. An engine is only asked for the types it
     * {@link io.uhndata.iap.search.spi.QuickSearchEngine#getSupportedTypes() declares support for}, so this may be a
     * subset of what it can search, and it must not return results of any other type.
     *
     * @return a non-empty list of node type names
     */
    @NotNull
    List<String> getResourceTypes();
}
