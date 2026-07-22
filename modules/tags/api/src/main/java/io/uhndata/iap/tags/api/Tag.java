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

import io.uhndata.iap.tags.models.TagDefinition;

/**
 * One tag effectively carried by a resource: the tag name, its definition, and how the tag reached the resource —
 * explicitly placed on it, inherited from a tagged ancestor, or aggregated from a tagged descendant. Instances are
 * immutable snapshots computed by the {@link TagManager}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class Tag
{
    /**
     * How a tag reached a resource.
     *
     * @since 0.1.0
     */
    public enum Origin
    {
        /** The tag is explicitly present in the resource's own {@code tags} property. */
        EXPLICIT,

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
     * @param name the tag name
     * @param definition the tag's definition, may be {@code null} for a tag string with no matching definition
     * @param origins how the tag reached the resource, at least one origin
     * @param sources the paths of the resources explicitly carrying the tag
     */
    public Tag(final String name, final TagDefinition definition, final Set<Origin> origins,
        final Set<String> sources)
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
    public String getName()
    {
        return this.name;
    }

    /**
     * The definition of the tag. A tag string present on a node without a matching definition under {@code /Tags},
     * e.g. left behind after its definition was deleted, has no definition.
     *
     * @return the tag definition, or {@code null} if the tag is undefined
     */
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
     * How the tag reached the resource. A tag may have more than one origin, e.g. both explicitly placed on the
     * resource and inherited from an ancestor.
     *
     * @return the origins, a non-empty read-only set
     */
    public Set<Origin> getOrigins()
    {
        return this.origins;
    }

    /**
     * The paths of the resources explicitly carrying the tag: the resource itself for an {@link Origin#EXPLICIT}
     * tag, the tagged ancestors for an {@link Origin#INHERITED} one, the tagged descendants for an
     * {@link Origin#AGGREGATED} one.
     *
     * @return the source paths, a non-empty read-only set
     */
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
