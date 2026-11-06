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
 * Reads the tag definitions, the {@code tag:Definition} nodes under {@value #DEFINITIONS_PATH}. To read, place or
 * remove the tags of some content, view its model as {@link Taggable} instead.
 *
 * <p>
 * The definitions are read with the manager's own service user, so a caller needs no session. An access control
 * entry on one definition therefore does not hide it from the manager's callers.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TagManager
{
    /** The property holding the tags placed on a piece of content, declared by the {@code tag:Taggable} mixin. */
    String TAGS_PROPERTY = "tags";

    /**
     * The mixin of a node that {@code aggregated} tags climb up to and no further. It carries the aggregate of the
     * content beneath it, and passes nothing to its ancestors. {@code data:EntityHomepage} declares it.
     */
    String BOUNDARY_MIXIN = "tag:Boundary";

    /**
     * The property set on content whose derived tags cannot be trusted, declared by the {@code tag:Taggable} mixin,
     * and absent when they can. Its value is {@link #STATE_FAILED} or {@link #STATE_RECOMPUTING}. The next commit
     * that reaches the node recomputes its tags in full, whatever the value.
     */
    String COMPUTATION_STATE_PROPERTY = "tagComputationState";

    /** A tag processor threw, and the stored derived tags are the last ones computed successfully. */
    String STATE_FAILED = "failed";

    /** A repair asked for the derived tags to be recomputed. */
    String STATE_RECOMPUTING = "recomputing";

    /** The path of the {@code tag:Homepage} node holding the tag definitions. */
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
