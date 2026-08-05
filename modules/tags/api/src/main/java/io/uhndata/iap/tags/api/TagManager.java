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
package io.uhndata.iap.tags.api;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.tags.models.TagDefinition;
import io.uhndata.iap.tags.models.Taggable;

/**
 * The tag vocabulary service, resolving the {@code iap:TagDefinition} nodes stored under
 * {@value #DEFINITIONS_PATH}. The operations on the tags themselves live on the models: view any content model as
 * {@link Taggable} to read, place, or remove its tags.
 *
 * <p>
 * The definitions are read through the manager's own service user: they are platform vocabulary, world-readable by
 * design, and the code most in need of them — commit hooks, scheduled jobs — often runs with no user session at
 * all.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TagManager
{
    /** The name of the property holding the tags of a piece of content, declared by the {@code iap:Taggable} mixin. */
    String TAGS_PROPERTY = "tags";

    /**
     * The name of the property marking content whose derived tags cannot be trusted, declared by the
     * {@code iap:Taggable} mixin. Absent when they can. See {@link #STATE_FAILED} and
     * {@link #STATE_RECOMPUTING} for the two values, and note that the property's presence, not its value, is what
     * drives the recomputation.
     */
    String COMPUTATION_STATE_PROPERTY = "tagComputationState";

    /** A tag processor threw: the stored derived tags are the last ones computed successfully. */
    String STATE_FAILED = "failed";

    /** The stored derived tags were declared untrustworthy and a recomputation was asked for. */
    String STATE_RECOMPUTING = "recomputing";

    /** The path of the {@code iap:TagsHomepage} node holding the tag definitions. */
    String DEFINITIONS_PATH = "/Tags";

    /**
     * All the defined tags, in display order.
     *
     * @return the tag definitions, an empty list if there are none
     */
    @NotNull
    List<TagDefinition> getDefinitions();

    /**
     * The definition of the given tag.
     *
     * @param name a tag name
     * @return the definition whose {@link TagDefinition#getName() name} is exactly {@code name}, or {@code null} if
     *         the tag is not defined
     */
    @Nullable
    TagDefinition getDefinition(@NotNull String name);

    /**
     * The defined tags matching the given filters, in display order.
     *
     * @param category if not blank, only definitions listing this category (ignoring case) are returned
     * @param query if not blank, only definitions containing this text (ignoring case) in their name, label, or
     *            description are returned
     * @return the matching tag definitions, an empty list if none match
     */
    @NotNull
    List<TagDefinition> findDefinitions(@Nullable String category, @Nullable String query);
}
