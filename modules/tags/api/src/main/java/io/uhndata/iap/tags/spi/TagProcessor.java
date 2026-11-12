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
package io.uhndata.iap.tags.spi;

import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;

/**
 * Computes the <em>derived</em> tags of a node: the tags nobody placed on it, copied from its surroundings or
 * computed from its content. A processor is an OSGi service, and a commit editor runs it on the nodes each commit
 * changes. The results are stored, so a query or a page reads them without walking the tree.
 *
 * <p>
 * Derived tags are stored per {@link Phase phase}, not per processor. The editor stores the union of what a phase's
 * processors computed in the one multivalued String property the phase {@link Phase#getPropertyName() owns}. It
 * removes the property when the union is empty. A query or an index can therefore name the properties without
 * knowing which processors are registered.
 * </p>
 *
 * <p>
 * A processor must compute a deterministic function of what its {@link #getScope() scope} lets it read, so that
 * recomputing in any order gives the same result. It may read state outside the repository, or outside its scope,
 * only if that state never changes while the node it tags exists. Nothing recomputes the tags when such state
 * changes.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TagProcessor
{
    /**
     * The phase of the tree traversal a processor runs in, which decides what it can read and the property its
     * results are stored in. Phases run in declaration order on each node: the ancestors' tags flow in first, the
     * node's own content is examined next, and the descendants' tags flow up last.
     *
     * @since 0.1.0
     */
    enum Phase
    {
        /**
         * Runs on the way down, before the node's children, when only the parent's aggregated tags may still be out
         * of date. This is the phase for copying tags from ancestors to descendants.
         */
        TOP_DOWN("inheritedTags"),

        /**
         * Runs on the way down, after {@link #TOP_DOWN} and before the node's children. The node's own content and
         * the tags it inherited are up to date, for tagging a node by the data it holds. A local processor must not
         * read its node's {@link #BOTTOM_UP} property, which is not yet recomputed. Reading it could also let a
         * computation cycle form, through the tags the node's descendants inherit.
         */
        LOCAL("computedTags"),

        /**
         * Runs on the way up, after the node's children, whose properties are all recomputed by then. This is the
         * phase for copying tags from descendants to ancestors.
         */
        BOTTOM_UP("aggregatedTags");

        private final String propertyName;

        Phase(final String propertyName)
        {
            this.propertyName = propertyName;
        }

        /**
         * The name of the multivalued String property holding the tags computed in this phase by all its processors.
         *
         * @return a property name
         */
        @NotNull
        public String getPropertyName()
        {
            return this.propertyName;
        }
    }

    /**
     * How much of the repository a processor may read, which is also how much of it makes the processor run again
     * when it changes. A tag that depends on something wider, such as another entity, cannot come from a processor.
     * A listener or a job can place it as a {@code system} tag after the fact, and it is stale until then.
     *
     * @since 0.1.0
     */
    enum Scope
    {
        /**
         * The processed node and its parent, and for a {@link Phase#BOTTOM_UP} processor its children. The processor
         * runs on the nodes a commit changes, and on the descendants the changed tags flow down to.
         */
        NODE,

        /**
         * The whole {@code data:Entity} subtree enclosing the processed node, for a tag that depends on more than one
         * part of an entity. When anything inside an entity changes, every entity-scoped processor runs again on every
         * node of that entity.
         */
        ENTITY,
    }

    /**
     * The phase this processor runs in, which also decides the property its results are stored in.
     *
     * @return the phase
     */
    @NotNull
    Phase getPhase();

    /**
     * How much of the repository this processor reads. The default is {@link Scope#NODE}.
     *
     * @return the scope
     */
    @NotNull
    default Scope getScope()
    {
        return Scope.NODE;
    }

    /**
     * The order processors run in within a phase, lowest first. The phase stores the union of their results, so the
     * order never changes the result, and one processor cannot affect another through it.
     *
     * @return a priority number
     */
    int getPriority();

    /**
     * Computes this processor's contribution to the tags stored in its {@link Phase#getPropertyName() phase's
     * property} on the node being processed.
     *
     * @param context the node being processed, its surroundings as far as this processor's scope allows, and the tag
     *            definitions in effect for this commit
     * @return the derived tags this processor contributes, an empty set if none apply
     */
    @NotNull
    Set<String> computeTags(@NotNull TagContext context);

    /**
     * Reads a multivalued String property of a node state as a set, such as {@code tags} or one of the derived tag
     * properties.
     *
     * @param node a node state
     * @param property the name of the property to read
     * @return the property values in storage order, an empty set if the property is missing
     */
    @NotNull
    static Set<String> readTags(@NotNull final NodeState node, @NotNull final String property)
    {
        final Set<String> result = new LinkedHashSet<>();
        final PropertyState values = node.getProperty(property);
        if (values == null) {
            return result;
        }
        if (values.isArray()) {
            values.getValue(Type.STRINGS).forEach(result::add);
        } else {
            result.add(values.getValue(Type.STRING));
        }
        return result;
    }
}
