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
package io.uhndata.iap.history.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.content.models.Content;

/**
 * The record's root at {@code /History}, and every bucket beneath it.
 *
 * <p>
 * One type for both, because the layout is a prefix tree: an action is filed under
 * {@code /History/<xx>/<yy>/<zz>/<action>}, each bucket named after the leading characters of the action's own name,
 * the way Oak files version histories and the way the archive files its entries. It is not a browsable hierarchy and
 * nothing reads it top-down — the point of the buckets is that no single parent ends up with hundreds of thousands of
 * children.
 * </p>
 *
 * <p>
 * Nobody reads this store directly. A service user reads it and adapts what it finds into what the person asking may
 * see, so what a given reader is shown is decided when the history is served rather than by permissions here.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Log.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Log extends Content
{
    /** The Sling resource type of the record's root and of the buckets under it. */
    public static final String RESOURCE_TYPE = "hist/Log";

    /**
     * The actions filed directly in this bucket.
     *
     * <p>
     * Empty at every level above the bottom of the prefix tree, where the children are further buckets instead. A
     * caller wanting one resource's history queries for its {@link Entry entries} rather than walking this.
     * </p>
     *
     * @return the actions, possibly empty, never {@code null}
     */
    @NotNull
    public List<Action> getActions()
    {
        return this.getChildren(Action.RESOURCE_TYPE, Action.class);
    }

    /**
     * The buckets under this one.
     *
     * @return the buckets, possibly empty, never {@code null}
     */
    @NotNull
    public List<Log> getBuckets()
    {
        return this.getChildren(Log.RESOURCE_TYPE, Log.class);
    }
}
