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
 * The resources of one type that reference a resource whose deletion was examined, blocking a non-recursive
 * deletion. Only a few of the resources are named: when the group is large, {@link #getCount()} exceeds the number
 * of {@link #getNames() names}.
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
     * @param nodeType the primary node type shared by the group
     * @param label a human-readable label for the type, e.g. {@code submission}
     * @param names display names for the first few members
     * @param count the total number of members, at least the number of names
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
     * The primary node type shared by the group.
     *
     * @return a node type name, e.g. {@code sub:Submission}
     */
    @NotNull
    public String getNodeType()
    {
        return this.nodeType;
    }

    /**
     * A human-readable label for the type.
     *
     * @return a lowercase singular noun, e.g. {@code submission}
     */
    @NotNull
    public String getLabel()
    {
        return this.label;
    }

    /**
     * Display names for the first few members of the group.
     *
     * @return an immutable list of names, possibly shorter than {@link #getCount()}
     */
    @NotNull
    public List<String> getNames()
    {
        return this.names;
    }

    /**
     * The total number of members.
     *
     * @return a positive number
     */
    public long getCount()
    {
        return this.count;
    }
}
