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
package io.uhndata.iap.utils;

import java.util.List;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NodeTypeUtils}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class NodeTypeUtilsTest
{
    private static final String SUPERTYPES = "rep:supertypes";

    private static final String VERSION = "x:Version";

    private static final String ENTITY = "data:Entity";

    private static final String MIXIN = "x:Tracked";

    private static final String TRACKABLE = "x:Trackable";

    /** A registry knowing a version that is an entity, and a mixin that is trackable. */
    private NodeState repository()
    {
        final NodeBuilder root = EmptyNodeState.EMPTY_NODE.builder();
        final NodeBuilder types = root.child("jcr:system").child("jcr:nodeTypes");
        types.child(VERSION).setProperty(SUPERTYPES, List.of(ENTITY, "nt:base"), Type.NAMES);
        types.child(MIXIN).setProperty(SUPERTYPES, List.of(TRACKABLE), Type.NAMES);
        return root.getNodeState();
    }

    @Test
    void findsTheRegistryUnderTheRoot()
    {
        assertTrue(NodeTypeUtils.registry(repository()).hasChildNode(VERSION));
    }

    @Test
    void aTypeIsOrExtendsItselfAndItsSupertypes()
    {
        final NodeState registry = NodeTypeUtils.registry(repository());

        assertTrue(NodeTypeUtils.isOrExtends(VERSION, VERSION, registry));
        assertTrue(NodeTypeUtils.isOrExtends(VERSION, ENTITY, registry));
        assertFalse(NodeTypeUtils.isOrExtends(VERSION, TRACKABLE, registry));
        assertFalse(NodeTypeUtils.isOrExtends("x:Unregistered", ENTITY, registry));
        assertFalse(NodeTypeUtils.isOrExtends(null, ENTITY, registry));
    }

    @Test
    void aNodeIsOfTypeByItsPrimaryTypeOrAMixin()
    {
        final NodeState registry = NodeTypeUtils.registry(repository());
        final NodeState node = EmptyNodeState.EMPTY_NODE.builder()
            .setProperty("jcr:primaryType", VERSION, Type.NAME)
            .setProperty("jcr:mixinTypes", List.of(MIXIN), Type.NAMES)
            .getNodeState();

        assertTrue(NodeTypeUtils.isOfType(node, ENTITY, registry));
        assertTrue(NodeTypeUtils.isOfType(node, TRACKABLE, registry));
        assertFalse(NodeTypeUtils.isOfType(node, "x:Other", registry));
        assertFalse(NodeTypeUtils.isOfType(EmptyNodeState.EMPTY_NODE, ENTITY, registry));
    }
}
