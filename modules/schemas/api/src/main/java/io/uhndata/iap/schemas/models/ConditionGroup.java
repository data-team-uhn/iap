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
package io.uhndata.iap.schemas.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

/**
 * A Sling Model wrapping a {@code sch:ConditionGroup} node: a set of conditions that can be imposed on e.g. the
 * display of a {@link Section} or the requiredness of a {@link Requirement}, combined with AND or OR depending on
 * {@link #isRequireAll()}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = Condition.class, resourceType = ConditionGroup.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ConditionGroup extends Condition
{
    /** The {@code sling:resourceType} of a {@code sch:ConditionGroup} node. */
    public static final String RESOURCE_TYPE = "sch/ConditionGroup";

    @ValueMapValue
    private boolean requireAll;

    /**
     * Whether every condition in this group must hold (AND), or just one (OR).
     *
     * @return {@code true} if all conditions are required
     */
    public boolean isRequireAll()
    {
        return this.requireAll;
    }

    /**
     * Every condition directly listed in this group, each adapted to its own specific model regardless of
     * whether it is a {@link SingleCondition}, a nested {@link ConditionGroup}, or any future {@link Condition}
     * subtype.
     *
     * @return a list of conditions, empty if none
     */
    public List<Condition> getConditions()
    {
        return this.getChildren(Condition.RESOURCE_TYPE, Condition.class);
    }
}
