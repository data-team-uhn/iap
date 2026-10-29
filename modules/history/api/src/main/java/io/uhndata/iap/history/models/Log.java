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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;

import io.uhndata.iap.content.models.Content;

/**
 * The node at {@code /History} holding the whole history, and each prefix-tree bucket under it.
 *
 * <p>
 * The layout is a prefix tree: an action is filed under {@code /History/<xx>/<yy>/<zz>/<action>}, each bucket named
 * after the leading characters of the action's own name. Nothing reads the tree top-down: one resource's history is
 * found by querying for its {@link Entry entries}.
 * </p>
 *
 * <p>
 * No person reads this store directly. What a person may see of it is decided when the history is served to them,
 * not by permissions here.
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
}
