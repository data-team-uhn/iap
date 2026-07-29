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
package io.uhndata.iap.tags.internal;

import java.util.List;
import java.util.Set;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.tags.spi.TagContext;
import io.uhndata.iap.tags.spi.TagDefinitions;
import io.uhndata.iap.tags.spi.TagProcessor;
import io.uhndata.iap.tags.spi.TagProcessor.Phase;
import io.uhndata.iap.tags.spi.TagProcessor.Scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TagInheritanceProcessor} and {@link TagAggregationProcessor} as pure functions.
 *
 * @version $Id$
 * @since 0.1.0
 */
class TagProcessorsTest
{
    private static final String TAGS = "tags";

    private static final String AGG = "agg";

    private static final String INH = "inh";

    /** Simple fixed definitions: {@code agg} is aggregated, {@code inh} is inheritable, {@code plain} is neither. */
    private final TagDefinitions definitions = new TagDefinitions()
    {
        @Override
        public Set<String> getNames()
        {
            return Set.of(AGG, INH, "plain");
        }

        @Override
        public boolean isDefined(final String name)
        {
            return getNames().contains(name);
        }

        @Override
        public boolean isAggregated(final String name)
        {
            return AGG.equals(name);
        }

        @Override
        public boolean isInheritable(final String name)
        {
            return INH.equals(name);
        }
    };

    private final TagInheritanceProcessor inheritance = new TagInheritanceProcessor();

    private final TagAggregationProcessor aggregation = new TagAggregationProcessor();

    @Test
    void describeThemselves()
    {
        assertEquals(Phase.TOP_DOWN, this.inheritance.getPhase());
        assertEquals(Phase.TOP_DOWN.getPropertyName(), TagInheritanceProcessor.PROPERTY);
        assertEquals(Scope.NODE, this.inheritance.getScope());
        assertEquals(100, this.inheritance.getPriority());
        assertEquals(Phase.BOTTOM_UP, this.aggregation.getPhase());
        assertEquals(Phase.BOTTOM_UP.getPropertyName(), TagAggregationProcessor.PROPERTY);
        assertEquals(Scope.NODE, this.aggregation.getScope());
        assertEquals(100, this.aggregation.getPriority());
    }

    @Test
    void eachPhaseOwnsOneProperty()
    {
        assertEquals("inheritedTags", Phase.TOP_DOWN.getPropertyName());
        assertEquals("computedTags", Phase.LOCAL.getPropertyName());
        assertEquals("aggregatedTags", Phase.BOTTOM_UP.getPropertyName());
    }

    @Test
    void inheritanceChainsAllTagsBelongingToTheParent()
    {
        final NodeBuilder parent = EmptyNodeState.EMPTY_NODE.builder();
        // Only inheritable tags flow down, from the parent's explicit, computed and already inherited tags alike
        parent.setProperty(TAGS, List.of(INH, AGG, "plain", "unknown"), Type.STRINGS);
        parent.setProperty(Phase.LOCAL.getPropertyName(), List.of(INH, "computedStale"), Type.STRINGS);
        parent.setProperty(TagInheritanceProcessor.PROPERTY, List.of(INH, "stale"), Type.STRINGS);

        assertEquals(Set.of(INH),
            this.inheritance.computeTags(context(EmptyNodeState.EMPTY_NODE, parent.getNodeState())));
    }

    @Test
    void inheritanceCarriesLocallyComputedTagsOfTheParent()
    {
        final NodeBuilder parent = EmptyNodeState.EMPTY_NODE.builder();
        // A tag no user placed, computed for the parent from its own content, still flows down
        parent.setProperty(Phase.LOCAL.getPropertyName(), List.of(INH), Type.STRINGS);

        assertEquals(Set.of(INH),
            this.inheritance.computeTags(context(EmptyNodeState.EMPTY_NODE, parent.getNodeState())));
    }

    @Test
    void repositoryRootInheritsNothing()
    {
        assertTrue(this.inheritance.computeTags(context(EmptyNodeState.EMPTY_NODE, null)).isEmpty());
    }

    @Test
    void aggregationChainsAllTagsBelongingToTheChildren()
    {
        final NodeBuilder node = EmptyNodeState.EMPTY_NODE.builder();
        // Only aggregated tags flow up, from the children's explicit, computed and already aggregated tags alike
        node.child("first").setProperty(TAGS, List.of(AGG, INH, "plain", "unknown"), Type.STRINGS);
        node.child("second").setProperty(TagAggregationProcessor.PROPERTY, List.of(AGG, "stale"), Type.STRINGS);
        node.child("third").setProperty(Phase.LOCAL.getPropertyName(), List.of(AGG, "computedStale"), Type.STRINGS);
        node.child("untagged");

        assertEquals(Set.of(AGG), this.aggregation.computeTags(context(node.getNodeState(), null)));
    }

    @Test
    void aggregationSkipsHiddenChildren()
    {
        final NodeBuilder node = EmptyNodeState.EMPTY_NODE.builder();
        node.child(":hidden").setProperty(TAGS, List.of(AGG), Type.STRINGS);

        assertTrue(this.aggregation.computeTags(context(node.getNodeState(), null)).isEmpty());
    }

    @Test
    void readTagsHandlesAllPropertyShapes()
    {
        final NodeBuilder node = EmptyNodeState.EMPTY_NODE.builder();
        // A single-valued property is read as a one-element set
        node.setProperty(TAGS, AGG);

        assertEquals(Set.of(AGG), TagProcessor.readTags(node.getNodeState(), TAGS));
        assertTrue(TagProcessor.readTags(node.getNodeState(), "missing").isEmpty());
        assertTrue(TagProcessor.readTags(null, TAGS).isEmpty());
    }

    @Test
    void nodeScopedProcessorsGetNoScopeRoot()
    {
        assertNull(context(EmptyNodeState.EMPTY_NODE, null).getScopeRoot());
    }

    /**
     * A context handing a processor one node and its parent, as the editor would for a node-scoped processor.
     *
     * @param node the node being processed
     * @param parent the node's parent, {@code null} for the repository root
     * @return a context backed by the fixed test definitions
     */
    private TagContext context(final NodeState node, final NodeState parent)
    {
        return new TagContext()
        {
            @Override
            public NodeState getNode()
            {
                return node;
            }

            @Override
            public NodeState getParent()
            {
                return parent;
            }

            @Override
            public String getPath()
            {
                return "/Test";
            }

            @Override
            public NodeState getScopeRoot()
            {
                return null;
            }

            @Override
            public TagDefinitions getDefinitions()
            {
                return TagProcessorsTest.this.definitions;
            }
        };
    }
}
