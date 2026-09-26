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
package io.uhndata.iap.schemas.models;

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.tags.models.Taggable;

/**
 * Where a schema or a schema version stands, read from the {@code lifecycle} tag placed on it: {@code draft},
 * {@code active} or {@code retired}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public enum LifecycleState
{
    /** Still being written: may change in any way, and cannot be answered. */
    DRAFT("draft"),

    /** Open to new submissions; frozen except for wording. */
    ACTIVE("active"),

    /** Closed to new submissions; existing submissions keep referencing it. */
    RETIRED("retired");

    private final String tag;

    LifecycleState(final String tag)
    {
        this.tag = tag;
    }

    /**
     * The name of the tag marking this state.
     *
     * @return a tag name
     */
    @NotNull
    public String getTag()
    {
        return this.tag;
    }

    /**
     * The state placed on a piece of content, looking only at its own tags.
     *
     * @param content the schema or schema version to read
     * @param untagged what content carrying no lifecycle tag is read as
     * @return the content's own state
     */
    @NotNull
    static LifecycleState of(@NotNull final Content content, @NotNull final LifecycleState untagged)
    {
        final Taggable tags = content.as(Taggable.class);
        if (tags != null) {
            for (final LifecycleState state : values()) {
                if (tags.hasOwnTag(state.tag)) {
                    return state;
                }
            }
        }
        return untagged;
    }

    /**
     * Whether a piece of content effectively carries the {@code retired} tag, placed on it or, since the tag is
     * inheritable, on an ancestor: a version of a retired schema is retired too.
     *
     * @param content the content to check
     * @return {@code true} if the content is retired, in its own right or by an ancestor
     */
    static boolean isRetired(@NotNull final Content content)
    {
        final Taggable tags = content.as(Taggable.class);
        return tags != null && tags.hasTag(RETIRED.tag);
    }
}
