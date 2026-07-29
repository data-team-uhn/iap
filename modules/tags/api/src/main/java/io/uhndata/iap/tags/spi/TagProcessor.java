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

/**
 * Computes the <em>derived</em> tags of a node: tags not explicitly placed on it, but implied by its surroundings, or
 * triggered by its content. Implementations are registered as OSGi services and invoked by a commit editor every time
 * the tags in a subtree change, or the subtree itself does, so that the derived tags are materialized in the
 * repository and both queries and status displays can read them directly, without walking the tree.
 *
 * <p>
 * Derived tags are stored per {@link Phase phase}, not per processor: all the processors of a phase contribute to the
 * single multivalued String property that phase {@link Phase#getPropertyName() owns}, and the editor stores the union
 * of what they computed, removing the property when that union is empty. A fixed, statically known set of property
 * names is what lets a query filter on derived tags without enumerating the registered processors, and what lets an
 * index be defined over them.
 * </p>
 *
 * <p>
 * Every processor must compute a deterministic function of the inputs its {@link #getScope() scope} grants it, so
 * that recomputing it in any order always converges to the same result. Which inputs those are differs by phase: the
 * propagating processors read their node's neighbors, while a {@link Phase#LOCAL} one reads the node's own content.
 * A processor may read state outside the repository, or outside its scope, only when that state is immutable for the
 * lifetime of the node it tags — nothing else re-triggers the computation when such state changes.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TagProcessor
{
    /**
     * The tree traversal phase in which a processor runs, which determines both the information available to it and
     * the property its results are stored in. Phases run in declaration order on each node: the ancestors' tags flow
     * in first, the node's own content is examined next, and the descendants' tags flow up last.
     *
     * @since 0.1.0
     */
    enum Phase
    {
        /**
         * Computed on the way down, before the node's children are processed; the parent's properties, already
         * recomputed in this commit, are available, e.g. for copying tags from ancestors to their descendants.
         */
        TOP_DOWN("inheritedTags"),

        /**
         * Computed on the way down, after {@link #TOP_DOWN} and before the node's children are processed; the node's
         * own content and the tags that flowed into it from its ancestors are available, e.g. for tagging a node
         * according to the data it holds. A local processor must not read the {@link #BOTTOM_UP} property of its own
         * node: reading what its own results feed into is what would let a computation cycle form.
         */
        LOCAL("computedTags"),

        /**
         * Computed on the way up, after all the node's children were processed; the children's properties, already
         * recomputed in this commit, are available, e.g. for copying tags from descendants to their ancestors.
         */
        BOTTOM_UP("aggregatedTags");

        private final String propertyName;

        Phase(final String propertyName)
        {
            this.propertyName = propertyName;
        }

        /**
         * The name of the multivalued String property holding the tags computed in this phase, shared by all the
         * processors running in it.
         *
         * @return a property name
         */
        public String getPropertyName()
        {
            return this.propertyName;
        }
    }

    /**
     * How much of the repository a processor may look at, and — equivalently — how much of it re-triggers the
     * processor when it changes. Anything beyond the widest scope, e.g. a tag depending on a sibling entity, cannot
     * be computed by a processor at all: it belongs in a listener or a job placing explicit {@code system} tags after
     * the fact, accepting the staleness that comes with it.
     *
     * @since 0.1.0
     */
    enum Scope
    {
        /**
         * The processed node and its parent. Recomputed when the tags of the node or of its neighbors change, which
         * is the cheapest and by far the most common case.
         */
        NODE,

        /**
         * The whole {@code iap:Entity} subtree enclosing the processed node, e.g. for a tag depending on more than
         * one of an entity's parts. Every such processor is recomputed on every node of an entity whenever anything
         * inside that entity changes, so the cost is bounded by the size of one entity, not of the repository.
         */
        ENTITY,
    }

    /**
     * The traversal phase in which this processor must be invoked, which also decides the property its results are
     * stored in.
     *
     * @return the phase
     */
    Phase getPhase();

    /**
     * How much of the repository this processor needs to see. Defaults to the cheap {@link Scope#NODE}.
     *
     * @return the scope
     */
    default Scope getScope()
    {
        return Scope.NODE;
    }

    /**
     * The order in which processors are invoked within a phase: processors with a lower priority are invoked first.
     * Since a phase's property holds the union of what its processors computed, and a union does not depend on the
     * order its members were added in, this only makes the invocation order deterministic; it is not a way for one
     * processor to influence another.
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
    Set<String> computeTags(TagContext context);

    /**
     * Reads a multivalued String property of a node state as a set, e.g. the explicit {@code tags} or one of the
     * derived tag properties maintained by the phases.
     *
     * @param node a node state, may be {@code null}
     * @param property the name of the property to read
     * @return the property values in storage order, an empty set if the node or the property is missing
     */
    static Set<String> readTags(final NodeState node, final String property)
    {
        final Set<String> result = new LinkedHashSet<>();
        if (node == null) {
            return result;
        }
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
