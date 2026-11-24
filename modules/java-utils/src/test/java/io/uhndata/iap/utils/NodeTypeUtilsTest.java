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

import java.util.Calendar;
import java.util.List;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.util.ISO8601;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final String THING = "x:Thing";

    private static final String PART = "x:Part";

    private static final String SOURCE = "source";

    private static final String OPERAND = "operand";

    private static final String NAMED_PROPERTIES = "rep:namedPropertyDefinitions";

    private static final String NAMED_CHILDREN = "rep:namedChildNodeDefinitions";

    private static final String AUTO_CREATED = "jcr:autoCreated";

    private static final String DEFAULT_VALUES = "jcr:defaultValues";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

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

    @Test
    void fillsInWhatTheTypesAutocreateThroughoutTheSubtree()
    {
        final NodeState registry = autocreatingRegistry();
        final Calendar now = Calendar.getInstance();
        final NodeBuilder node = EmptyNodeState.EMPTY_NODE.builder()
            .setProperty(PRIMARY_TYPE, THING, Type.NAME)
            .setProperty("jcr:mixinTypes", List.of(MIXIN), Type.NAMES)
            .setProperty("kept", "mine");
        node.child("given").setProperty(PRIMARY_TYPE, PART, Type.NAME).setProperty(SOURCE, "tags");
        node.child("untyped");

        NodeTypeUtils.autoCreateItems(node, registry, "alice", now);

        assertEquals("x/Thing", node.getString("sling:resourceType"));
        assertEquals(List.of("a", "b"), node.getProperty("labels").getValue(Type.STRINGS));
        assertEquals("mine", node.getString("kept"));
        assertTrue(node.getBoolean("tracked"));
        assertEquals(36, node.getString("jcr:uuid").length());
        assertEquals(ISO8601.format(now), node.getProperty("jcr:created").getValue(Type.DATE));
        assertEquals("alice", node.getString("jcr:createdBy"));
        // Neither an empty default nor a missing one gives a value, and a definition that does not autocreate is
        // not applied at all
        assertFalse(node.hasProperty("empty"));
        assertFalse(node.hasProperty("unset"));
        assertFalse(node.hasProperty("optional"));
        // The new child is filled in too, and so is the one that was already there
        assertEquals(PART, node.getChildNode(OPERAND).getName(PRIMARY_TYPE));
        assertEquals("literal", node.getChildNode(OPERAND).getString(SOURCE));
        assertEquals("tags", node.getChildNode("given").getString(SOURCE));
        assertFalse(node.hasChildNode("manual"));
        assertFalse(node.hasChildNode("anonymous"));
    }

    @Test
    void recordsNobodyWithNoUser()
    {
        final NodeBuilder node = EmptyNodeState.EMPTY_NODE.builder().setProperty(PRIMARY_TYPE, THING, Type.NAME);

        NodeTypeUtils.autoCreateItems(node, autocreatingRegistry(), null, Calendar.getInstance());

        assertEquals("", node.getString("jcr:lastModifiedBy"));
    }

    @Test
    void fillingTwiceChangesNothing()
    {
        final NodeState registry = autocreatingRegistry();
        final NodeBuilder node = EmptyNodeState.EMPTY_NODE.builder().setProperty(PRIMARY_TYPE, THING, Type.NAME);
        NodeTypeUtils.autoCreateItems(node, registry, "alice", Calendar.getInstance());
        final NodeState once = node.getNodeState();

        NodeTypeUtils.autoCreateItems(node, registry, "bob", Calendar.getInstance());

        assertEquals(once, node.getNodeState());
    }

    /**
     * A registry laid out the way Oak flattens it: {@code x:Thing} autocreates a range of properties and an
     * {@code operand} child of type {@code x:Part}, and the mixin autocreates one more property.
     */
    private NodeState autocreatingRegistry()
    {
        final NodeBuilder root = EmptyNodeState.EMPTY_NODE.builder();
        final NodeBuilder types = root.child("jcr:system").child("jcr:nodeTypes");
        final NodeBuilder thing = types.child(THING).child(NAMED_PROPERTIES);
        autocreated(thing, "sling:resourceType", "STRING").setProperty(DEFAULT_VALUES, List.of("x/Thing"),
            Type.STRINGS);
        autocreated(thing, "labels", "STRINGS").setProperty("jcr:multiple", true)
            .setProperty(DEFAULT_VALUES, List.of("a", "b"), Type.STRINGS);
        autocreated(thing, "kept", "STRING").setProperty(DEFAULT_VALUES, List.of("theirs"), Type.STRINGS);
        autocreated(thing, "empty", "STRING").setProperty(DEFAULT_VALUES, List.of(), Type.STRINGS);
        autocreated(thing, "unset", "STRING");
        thing.child("optional").child("STRING").setProperty(DEFAULT_VALUES, List.of("x"), Type.STRINGS);
        autocreated(thing, "rep:primaryType", "NAME");
        autocreated(thing, "rep:uuid", "STRING");
        autocreated(thing, "jcr:created", "DATE");
        autocreated(thing, "jcr:createdBy", "STRING");
        autocreated(thing, "jcr:lastModified", "DATE");
        autocreated(thing, "jcr:lastModifiedBy", "STRING");
        final NodeBuilder children = types.child(THING).child(NAMED_CHILDREN);
        // A name with a definition that does not autocreate ahead of one that does
        children.child(OPERAND).child("nt:base");
        autocreated(children, OPERAND, PART).setProperty("jcr:defaultPrimaryType", PART, Type.NAME);
        children.child("manual").child(PART).setProperty("jcr:defaultPrimaryType", PART, Type.NAME);
        autocreated(children, "anonymous", "nt:base");
        autocreated(types.child(PART).child(NAMED_PROPERTIES), SOURCE, "STRING")
            .setProperty(DEFAULT_VALUES, List.of("literal"), Type.STRINGS);
        autocreated(types.child(MIXIN).child(NAMED_PROPERTIES), "tracked", "BOOLEAN")
            .setProperty(DEFAULT_VALUES, List.of(true), Type.BOOLEANS);
        return NodeTypeUtils.registry(root.getNodeState());
    }

    private static NodeBuilder autocreated(final NodeBuilder definitions, final String name, final String key)
    {
        return definitions.child(name).child(key).setProperty(AUTO_CREATED, true);
    }
}
