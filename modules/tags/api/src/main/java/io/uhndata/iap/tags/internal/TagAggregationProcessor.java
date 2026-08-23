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
 * Copies {@link TagDefinitions#isAggregated aggregated} tags up the tree: a node's {@code aggregatedTags} property
 * holds every aggregated tag placed on any of its descendants, materialized by chaining each child's own tags — the
 * explicit ones and the ones a {@link Phase#LOCAL} processor computed for it — with what that child already
 * aggregated from its own descendants.
 *
 * <p>
 * The chain is what carries a tag up, so it is also where the climb has to end: aggregating over
 * {@link TagContext#getAggregationSources() the children that may contribute} rather than over all of them is what
 * makes an {@code tag:Boundary} opaque from above. Reading a boundary child's own chain link would carry its
 * content's tags straight past it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class TagAggregationProcessor implements TagProcessor
{
    /** The name of the property holding the tags aggregated from descendants. */
    public static final String PROPERTY = Phase.BOTTOM_UP.getPropertyName();

    /**
     * The child properties an aggregated tag can reach this node through: the tags belonging to the child itself,
     * and the chain of what the child aggregated from its own descendants.
     */
    private static final List<String> SOURCES =
        List.of(TagManager.TAGS_PROPERTY, Phase.LOCAL.getPropertyName(), PROPERTY);

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
        // The chained values were already filtered when the child's own properties were computed, but re-filtering
        // sheds values left over from a definition that stopped being aggregated
        return StreamSupport.stream(context.getAggregationSources().spliterator(), false)
            .map(ChildNodeEntry::getNodeState)
            .flatMap(TagAggregationProcessor::sourceTags)
            .filter(definitions::isAggregated)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * All the tags of one child node that can be aggregated further up, whatever property they are stored in.
     *
     * @param child the state of a child node
     * @return the child's own and already aggregated tag names, unfiltered
     */
    private static Stream<String> sourceTags(final NodeState child)
    {
        return SOURCES.stream().flatMap(property -> TagProcessor.readTags(child, property).stream());
    }
}
