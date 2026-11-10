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
package io.uhndata.iap.submissions.internal.transfer;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;

/**
 * What a node's types let it hold, for the properties a transfer adds rather than copies.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class NodeTypes
{
    private NodeTypes()
    {
        // Static helpers only
    }

    /**
     * Whether a node may be given a property. A type that takes no properties of its own, such as {@code nt:file},
     * may not.
     *
     * @param node the node
     * @param name the property's name
     * @param value its value
     * @return {@code true} if its primary type or one of its mixins admits the property
     * @throws RepositoryException when the node's types cannot be read
     */
    static boolean accepts(final Node node, final String name, final Value value) throws RepositoryException
    {
        if (node.getPrimaryNodeType().canSetProperty(name, value)) {
            return true;
        }
        for (final NodeType mixin : node.getMixinNodeTypes()) {
            if (mixin.canSetProperty(name, value)) {
                return true;
            }
        }
        return false;
    }
}
