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
package io.uhndata.iap.workflows;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;

/**
 * Reads and writes the order of an orderable node's children below the JCR API, where the vocabulary is read and
 * the parsed nodes are written. Oak keeps that order in a hidden property, which a node state's own listing ignores
 * and which only the JCR API maintains by itself.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class OrderedChildren
{
    /** The node type of a container that takes children of any name and type, and keeps them in order. */
    static final String NT_UNSTRUCTURED_TYPE = "nt:unstructured";

    private static final String CHILD_ORDER_PROPERTY = ":childOrder";

    private OrderedChildren()
    {
        // Utility class, no instances allowed
    }

    /**
     * The names of a node's children in the order the JCR API lists them. A child the stored order does not name
     * comes after, in listing order. A name the order holds for no child is returned too, and reads as a node that
     * does not exist.
     *
     * @param parent the node whose children are listed
     * @return the child names, in order
     */
    static Set<String> names(final NodeState parent)
    {
        final Set<String> result = new LinkedHashSet<>();
        parent.getNames(CHILD_ORDER_PROPERTY).forEach(result::add);
        parent.getChildNodeNames().forEach(result::add);
        return result;
    }

    /**
     * Stores the order of a node's children.
     *
     * @param parent the node whose children are ordered
     * @param names the names of its children, in order
     */
    static void store(final NodeBuilder parent, final List<String> names)
    {
        parent.setProperty(CHILD_ORDER_PROPERTY, names, Type.NAMES);
    }
}
