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
import java.util.Set;

import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.spi.TagDefinitions;
import io.uhndata.iap.tags.spi.TagProcessor;

/**
 * Copies {@link TagDefinitions#isAggregated aggregated} tags up the tree: a node's {@code aggregatedTags} property
 * holds every aggregated tag explicitly placed on any of its descendants, materialized by chaining the children's
 * explicit and aggregated tags.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class TagAggregationProcessor implements TagProcessor
{
    /** The name of the property holding the tags aggregated from descendants. */
    public static final String PROPERTY = "aggregatedTags";

    @Override
    public Phase getPhase()
    {
        return Phase.BOTTOM_UP;
    }

    @Override
    public String getPropertyName()
    {
        return PROPERTY;
    }

    @Override
    public int getPriority()
    {
        return 100;
    }

    @Override
    public Set<String> computeTags(final NodeState node, final NodeState parent, final TagDefinitions definitions)
    {
        final Set<String> result = new LinkedHashSet<>();
        for (final ChildNodeEntry entry : node.getChildNodeEntries()) {
            if (entry.getName().charAt(0) == ':') {
                // Hidden, non-JCR-visible child
                continue;
            }
            final NodeState child = entry.getNodeState();
            for (final String tag : TagProcessor.readTags(child, TagManager.TAGS_PROPERTY)) {
                if (definitions.isAggregated(tag)) {
                    result.add(tag);
                }
            }
            // Already filtered when the child's own property was computed, but re-filter to shed stale values
            for (final String tag : TagProcessor.readTags(child, PROPERTY)) {
                if (definitions.isAggregated(tag)) {
                    result.add(tag);
                }
            }
        }
        return result;
    }
}
