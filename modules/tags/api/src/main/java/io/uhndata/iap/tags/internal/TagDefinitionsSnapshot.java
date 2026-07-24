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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import io.uhndata.iap.tags.spi.TagDefinitions;

/**
 * A {@link TagDefinitions} snapshot built by reading the {@code iap:TagDefinition} children of the {@code /Tags}
 * node state at the start of a commit.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class TagDefinitionsSnapshot implements TagDefinitions
{
    private final Set<String> names = new HashSet<>();

    private final Set<String> aggregated = new HashSet<>();

    private final Set<String> inheritable = new HashSet<>();

    /**
     * Basic constructor.
     *
     * @param homepage the state of the {@code /Tags} node holding the definitions; a missing node state yields an
     *            empty set of definitions
     */
    public TagDefinitionsSnapshot(final NodeState homepage)
    {
        for (final ChildNodeEntry child : homepage.getChildNodeEntries()) {
            final NodeState definition = child.getNodeState();
            if (!isTagDefinition(definition)) {
                continue;
            }
            final String name = getName(definition, child.getName());
            this.names.add(name);
            if (getFlag(definition, "aggregated")) {
                this.aggregated.add(name);
            }
            if (getFlag(definition, "inheritable")) {
                this.inheritable.add(name);
            }
        }
    }

    @Override
    public Set<String> getNames()
    {
        return Collections.unmodifiableSet(this.names);
    }

    @Override
    public boolean isDefined(final String name)
    {
        return this.names.contains(name);
    }

    @Override
    public boolean isAggregated(final String name)
    {
        return this.aggregated.contains(name);
    }

    @Override
    public boolean isInheritable(final String name)
    {
        return this.inheritable.contains(name);
    }

    private boolean isTagDefinition(final NodeState definition)
    {
        final PropertyState resourceType = definition.getProperty("sling:resourceType");
        if (resourceType != null && !resourceType.isArray()
            && "iap/TagDefinition".equals(resourceType.getValue(Type.STRING))) {
            return true;
        }
        final PropertyState primaryType = definition.getProperty("jcr:primaryType");
        return primaryType != null && !primaryType.isArray()
            && "iap:TagDefinition".equals(primaryType.getValue(Type.NAME));
    }

    private String getName(final NodeState definition, final String nodeName)
    {
        final PropertyState name = definition.getProperty("name");
        if (name == null || name.isArray() || name.getValue(Type.STRING).isEmpty()) {
            return nodeName;
        }
        return name.getValue(Type.STRING);
    }

    private boolean getFlag(final NodeState definition, final String name)
    {
        final PropertyState flag = definition.getProperty(name);
        return flag != null && !flag.isArray() && flag.getValue(Type.BOOLEAN);
    }
}
