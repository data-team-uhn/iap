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
import org.junit.jupiter.api.Test;

import io.uhndata.iap.tags.spi.TagDefinitions;
import io.uhndata.iap.tags.spi.TagProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(TagProcessor.Phase.TOP_DOWN, this.inheritance.getPhase());
        assertEquals(TagInheritanceProcessor.PROPERTY, this.inheritance.getPropertyName());
        assertEquals(100, this.inheritance.getPriority());
        assertEquals(TagProcessor.Phase.BOTTOM_UP, this.aggregation.getPhase());
        assertEquals(TagAggregationProcessor.PROPERTY, this.aggregation.getPropertyName());
        assertEquals(100, this.aggregation.getPriority());
    }

    @Test
    void inheritanceChainsParentExplicitAndInheritedTags()
    {
        final NodeBuilder parent = EmptyNodeState.EMPTY_NODE.builder();
        // Only inheritable tags flow down, both from the parent's explicit and already inherited tags
        parent.setProperty(TAGS, List.of(INH, AGG, "plain", "unknown"), Type.STRINGS);
        parent.setProperty(TagInheritanceProcessor.PROPERTY, List.of(INH, "stale"), Type.STRINGS);

        assertEquals(Set.of(INH), this.inheritance.computeTags(EmptyNodeState.EMPTY_NODE,
            parent.getNodeState(), this.definitions));
    }

    @Test
    void repositoryRootInheritsNothing()
    {
        assertTrue(this.inheritance.computeTags(EmptyNodeState.EMPTY_NODE, null, this.definitions).isEmpty());
    }

    @Test
    void aggregationChainsChildrenExplicitAndAggregatedTags()
    {
        final NodeBuilder node = EmptyNodeState.EMPTY_NODE.builder();
        // Only aggregated tags flow up, both from the children's explicit and already aggregated tags
        node.child("first").setProperty(TAGS, List.of(AGG, INH, "plain", "unknown"), Type.STRINGS);
        node.child("second").setProperty(TagAggregationProcessor.PROPERTY, List.of(AGG, "stale"), Type.STRINGS);
        node.child("untagged");

        assertEquals(Set.of(AGG), this.aggregation.computeTags(node.getNodeState(), null, this.definitions));
    }

    @Test
    void aggregationSkipsHiddenChildren()
    {
        final NodeBuilder node = EmptyNodeState.EMPTY_NODE.builder();
        node.child(":hidden").setProperty(TAGS, List.of(AGG), Type.STRINGS);

        assertTrue(this.aggregation.computeTags(node.getNodeState(), null, this.definitions).isEmpty());
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
}
