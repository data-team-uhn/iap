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

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.tags.models.TagDefinition;

/**
 * One tag a piece of content carries: its name, its definition, and how it reached the content. It may be placed on
 * the content, computed from the content, inherited from an ancestor, or aggregated from a descendant. Instances are
 * immutable, and {@link io.uhndata.iap.tags.models.Taggable#getEffectiveTags()} builds them.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class Tag
{
    /**
     * How a tag reached a piece of content.
     *
     * @since 0.1.0
     */
    public enum Origin
    {
        /** The tag is in the content's own {@code tags} property. */
        EXPLICIT,

        /** A {@code LOCAL} tag processor computed the tag from the content itself. */
        COMPUTED,

        /** The tag is {@link TagDefinition#isInheritable() inherited} from a tagged ancestor. */
        INHERITED,

        /** The tag is {@link TagDefinition#isAggregated() aggregated} from a tagged descendant. */
        AGGREGATED,
    }

    private final String name;

    private final TagDefinition definition;

    private final Set<Origin> origins;

    private final Set<String> sources;

    /**
     * Basic constructor.
     *
     * @param name the tag name, as stored in a {@code tags} property
     * @param definition the tag's definition, {@code null} if no definition under {@code /Tags} has this name
     * @param origins how the tag reached the content, at least one origin
     * @param sources the paths of the content the tag belongs to, as {@link #getSources()} lists them
     */
    public Tag(@NotNull final String name, @Nullable final TagDefinition definition,
        @NotNull final Set<Origin> origins, @NotNull final Set<String> sources)
    {
        this.name = name;
        this.definition = definition;
        this.origins = Collections.unmodifiableSet(
            origins.isEmpty() ? EnumSet.noneOf(Origin.class) : EnumSet.copyOf(origins));
        this.sources = Collections.unmodifiableSet(new LinkedHashSet<>(sources));
    }

    /**
     * The name of the tag, as stored in the {@code tags} property.
     *
     * @return the tag name
     */
    @NotNull
    public String getName()
    {
        return this.name;
    }

    /**
     * The definition of the tag. A tag string can outlive its definition, when the definition is deleted.
     *
     * @return the tag definition, or {@code null} if no definition under {@code /Tags} has this name
     */
    @Nullable
    public TagDefinition getDefinition()
    {
        return this.definition;
    }

    /**
     * Whether the tag has a matching definition under {@code /Tags}.
     *
     * @return {@code true} if {@link #getDefinition()} is not {@code null}
     */
    public boolean isDefined()
    {
        return this.definition != null;
    }

    /**
     * How the tag reached the content. A tag may have more than one origin, such as placed on the content and also
     * inherited from an ancestor.
     *
     * @return the origins, a non-empty read-only set
     */
    @NotNull
    public Set<Origin> getOrigins()
    {
        return this.origins;
    }

    /**
     * The paths of the content the tag belongs to. For an {@link Origin#EXPLICIT} or {@link Origin#COMPUTED} tag,
     * that is the described content itself. For an {@link Origin#INHERITED} tag, they are the tagged ancestors, and
     * for an {@link Origin#AGGREGATED} one, the tagged descendants.
     *
     * @return the source paths, a non-empty read-only set
     */
    @NotNull
    public Set<String> getSources()
    {
        return this.sources;
    }

    @Override
    public boolean equals(final Object other)
    {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Tag)) {
            return false;
        }
        return Objects.equals(this.name, ((Tag) other).name);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(this.name);
    }

    @Override
    public String toString()
    {
        return this.name + this.origins;
    }
}
