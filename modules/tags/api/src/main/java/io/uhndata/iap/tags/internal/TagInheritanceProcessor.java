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

import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.spi.TagContext;
import io.uhndata.iap.tags.spi.TagDefinitions;
import io.uhndata.iap.tags.spi.TagProcessor;

/**
 * Copies {@link TagDefinitions#isInheritable inheritable} tags down the tree: a node's {@code inheritedTags} property
 * holds every inheritable tag placed on any of its ancestors, materialized by chaining the parent's own tags — the
 * explicit ones and the ones a {@link Phase#LOCAL} processor computed for it — with what the parent itself inherited.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class TagInheritanceProcessor implements TagProcessor
{
    /** The name of the property holding the tags inherited from ancestors. */
    public static final String PROPERTY = Phase.TOP_DOWN.getPropertyName();

    /**
     * The parent properties an inheritable tag can reach this node through: the tags belonging to the parent itself,
     * and the chain of what the parent inherited from its own ancestors.
     */
    private static final List<String> SOURCES =
        List.of(TagManager.TAGS_PROPERTY, Phase.LOCAL.getPropertyName(), PROPERTY);

    @Override
    public Phase getPhase()
    {
        return Phase.TOP_DOWN;
    }

    @Override
    public int getPriority()
    {
        return 100;
    }

    @Override
    public Set<String> computeTags(final TagContext context)
    {
        final NodeState parent = context.getParent();
        if (parent == null) {
            return Set.of();
        }
        final TagDefinitions definitions = context.getDefinitions();
        // The chained values were already filtered when the parent's own properties were computed, but re-filtering
        // sheds values left over from a definition that stopped being inheritable
        return SOURCES.stream()
            .flatMap(property -> TagProcessor.readTags(parent, property).stream())
            .filter(definitions::isInheritable)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
