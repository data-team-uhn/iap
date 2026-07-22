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

import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.spi.TagDefinitions;
import io.uhndata.iap.tags.spi.TagProcessor;

/**
 * Copies {@link TagDefinitions#isInheritable inheritable} tags down the tree: a node's {@code inheritedTags}
 * property holds every inheritable tag explicitly placed on any of its ancestors, materialized by chaining the
 * parent's explicit and inherited tags.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class TagInheritanceProcessor implements TagProcessor
{
    /** The name of the property holding the tags inherited from ancestors. */
    public static final String PROPERTY = "inheritedTags";

    @Override
    public Phase getPhase()
    {
        return Phase.TOP_DOWN;
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
        if (parent == null) {
            return result;
        }
        for (final String tag : TagProcessor.readTags(parent, TagManager.TAGS_PROPERTY)) {
            if (definitions.isInheritable(tag)) {
                result.add(tag);
            }
        }
        // Already filtered when the parent's own property was computed, but re-filter to shed stale values
        for (final String tag : TagProcessor.readTags(parent, PROPERTY)) {
            if (definitions.isInheritable(tag)) {
                result.add(tag);
            }
        }
        return result;
    }
}
