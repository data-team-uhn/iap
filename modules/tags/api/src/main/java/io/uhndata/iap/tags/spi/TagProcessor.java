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
 * Computes the <em>derived</em> tags of a node: tags not explicitly placed on it, but implied by its surroundings,
 * e.g. aggregated from its descendants or inherited from its ancestors. Implementations are registered as OSGi
 * services and invoked one after another, in ascending {@link #getPriority() priority} order, by a commit editor
 * every time the explicit or derived tags in a subtree change, so that the derived tags are materialized in the
 * repository and both queries and status displays can read them directly, without walking the tree.
 *
 * <p>
 * Each processor owns one multivalued String property, named by {@link #getPropertyName()}, on every affected node:
 * the editor stores the {@link #computeTags computed} tags in it, removes it when the computed set is empty, and
 * propagates recomputation to the neighboring nodes whenever the stored value changes. The computation must be a
 * pure function of the given node, its parent, and the tag definitions, so that recomputing it in any order always
 * converges to the same result.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TagProcessor
{
    /**
     * The tree traversal phase in which a processor's property is computed, which determines the information
     * available to it: its own subtree, or the chain of its ancestors.
     *
     * @since 0.1.0
     */
    enum Phase
    {
        /**
         * Computed on the way down, before the node's children are processed; the parent's properties, already
         * recomputed in this commit, are available, e.g. for copying tags from ancestors to their descendants.
         */
        TOP_DOWN,

        /**
         * Computed on the way up, after all the node's children were processed; the children's properties, already
         * recomputed in this commit, are available, e.g. for copying tags from descendants to their ancestors.
         */
        BOTTOM_UP,
    }

    /**
     * The traversal phase in which this processor must be invoked.
     *
     * @return the phase
     */
    Phase getPhase();

    /**
     * The name of the multivalued String property this processor maintains on affected nodes. Each processor must
     * use its own, distinct property.
     *
     * @return a property name
     */
    String getPropertyName();

    /**
     * The order in which processors are invoked within a phase: processors with a lower priority are invoked first.
     *
     * @return a priority number
     */
    int getPriority();

    /**
     * Computes the tags to store in {@link #getPropertyName() this processor's property} on the given node.
     *
     * @param node the current state of the processed node
     * @param parent the current state of the processed node's parent, {@code null} for the repository root; in the
     *            {@link Phase#TOP_DOWN} phase the parent's derived tags are already recomputed
     * @param definitions the tag definitions in effect for this commit
     * @return the derived tags of the node, an empty set (meaning the property is removed) if none apply
     */
    Set<String> computeTags(NodeState node, NodeState parent, TagDefinitions definitions);

    /**
     * Reads a multivalued String property of a node state as a set, e.g. the explicit {@code tags} or one of the
     * derived tag properties maintained by processors.
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
