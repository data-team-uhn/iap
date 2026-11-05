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
package io.uhndata.iap.deletion.api;

import java.util.List;

import org.jetbrains.annotations.NotNull;

/**
 * The resources of one node type that block a deletion that is not recursive. Each references a resource to delete,
 * directly or through a link. Only the first few are named, so {@link #getCount()} can exceed the number of
 * {@link #getNames() names}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ReferrerGroup
{
    private final String nodeType;

    private final String label;

    private final List<String> names;

    private final long count;

    /**
     * Record a group of referrers.
     *
     * @param nodeType the primary node type of every resource in the group, such as {@code sub:Submission}
     * @param label the node type as a lowercase singular noun, such as {@code submission}
     * @param names the display names of the first few resources: each one's {@code title}, or its node name if it
     *            has none
     * @param count how many resources the group holds, at least as many as there are names
     */
    public ReferrerGroup(@NotNull final String nodeType, @NotNull final String label,
        @NotNull final List<String> names, final long count)
    {
        this.nodeType = nodeType;
        this.label = label;
        this.names = List.copyOf(names);
        this.count = count;
    }

    /**
     * The primary node type of every resource in the group.
     *
     * @return a node type name, such as {@code sub:Submission}
     */
    @NotNull
    public String getNodeType()
    {
        return this.nodeType;
    }

    /**
     * The node type in words.
     *
     * @return a lowercase singular noun, such as {@code submission} or {@code workflow version}
     */
    @NotNull
    public String getLabel()
    {
        return this.label;
    }

    /**
     * The display names of the first few resources in the group.
     *
     * @return an immutable list of names; fewer than {@link #getCount()} if the group is large
     */
    @NotNull
    public List<String> getNames()
    {
        return this.names;
    }

    /**
     * How many resources the group holds.
     *
     * @return a positive number
     */
    public long getCount()
    {
        return this.count;
    }
}
