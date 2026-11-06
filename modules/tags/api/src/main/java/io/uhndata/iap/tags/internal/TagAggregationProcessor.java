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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.spi.TagContext;
import io.uhndata.iap.tags.spi.TagDefinitions;
import io.uhndata.iap.tags.spi.TagProcessor;

/**
 * Copies {@link TagDefinitions#isAggregated aggregated} tags up the tree. A node's {@code aggregatedTags} property
 * holds every aggregated tag that belongs to a descendant below the nearest {@code tag:Boundary}. Each child passes
 * up its own tags, placed or computed by a {@link Phase#LOCAL} processor, and what it already aggregated.
 *
 * <p>
 * It reads only {@link TagContext#getAggregationSources() the children that may contribute}, never the whole child
 * list. Reading a boundary child would carry its content's tags past it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class TagAggregationProcessor implements TagProcessor
{
    /** The name of the property holding the tags aggregated from descendants. */
    public static final String AGGREGATED_TAGS_PROPERTY = Phase.BOTTOM_UP.getPropertyName();

    /** The properties of a child that its aggregated tags come from: its own tags, and what it aggregated. */
    private static final List<String> SOURCE_PROPERTIES =
        List.of(TagManager.TAGS_PROPERTY, Phase.LOCAL.getPropertyName(), AGGREGATED_TAGS_PROPERTY);

    @Override
    public Phase getPhase()
    {
        return Phase.BOTTOM_UP;
    }

    @Override
    public int getPriority()
    {
        return 100;
    }

    @Override
    public Set<String> computeTags(final TagContext context)
    {
        final TagDefinitions definitions = context.getDefinitions();
        // Filtered by the current definitions, which also drops the copies of a tag that stopped being aggregated
        return StreamSupport.stream(context.getAggregationSources().spliterator(), false)
            .map(ChildNodeEntry::getNodeState)
            .flatMap(TagAggregationProcessor::sourceTags)
            .filter(definitions::isAggregated)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * All the tags of one child that may be aggregated further up, from every property they are stored in.
     *
     * @param child the state of a child node
     * @return the child's own tag names and those it aggregated, not yet filtered by definition
     */
    private static Stream<String> sourceTags(final NodeState child)
    {
        return SOURCE_PROPERTIES.stream().flatMap(property -> TagProcessor.readTags(child, property).stream());
    }
}
