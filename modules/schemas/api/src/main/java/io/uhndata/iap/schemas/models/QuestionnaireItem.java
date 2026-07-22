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

import io.uhndata.iap.entities.models.EntityPart;

/**
 * The abstract base shared by everything that may appear in the body of a questionnaire: a single
 * {@link Question}, or a {@link Section} grouping more items. Corresponds to the {@code sch:QuestionnaireItem}
 * node type. Every item, of any subtype, may carry conditions controlling whether it is shown to the submitter.
 * Unlike {@link io.uhndata.iap.entities.models.Entity} or {@link Requirement}, this class is deliberately not
 * itself a registered Sling Model (no {@code @Model} annotation): each subtype instead declares
 * {@code adapters = QuestionnaireItem.class} on its own {@code @Model}, so
 * {@code resource.adaptTo(QuestionnaireItem.class)} dispatches to the actual concrete subtype (via Sling Models'
 * {@code ResourceTypeBasedResourcePicker}), instead of yielding a generic {@code QuestionnaireItem} instance
 * lacking a subtype's own fields. This lets a new item type plug in later without any change here or to the
 * callers that ask for "the items" of a section or questionnaire, at the cost of {@code QuestionnaireItem} no
 * longer being directly adaptable to on its own.
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class QuestionnaireItem extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sch:QuestionnaireItem} node. */
    public static final String RESOURCE_TYPE = "sch/QuestionnaireItem";

    /**
     * The single conditions controlling whether this item is shown to the submitter.
     *
     * @return a list of single conditions, empty if none
     */
    public List<SingleCondition> getSingleConditions()
    {
        return this.getChildren(SingleCondition.RESOURCE_TYPE, SingleCondition.class);
    }

    /**
     * The condition groups controlling whether this item is shown to the submitter.
     *
     * @return a list of condition groups, empty if none
     */
    public List<ConditionGroup> getConditionGroups()
    {
        return this.getChildren(ConditionGroup.RESOURCE_TYPE, ConditionGroup.class);
    }

    /**
     * Every condition controlling whether this item is shown to the submitter, whether a {@link SingleCondition}
     * or a {@link ConditionGroup} (or any future {@link Condition} subtype), each adapted to its own specific model.
     *
     * @return a list of conditions, empty if none
     */
    public List<Condition> getConditions()
    {
        return this.getChildren(Condition.RESOURCE_TYPE, Condition.class);
    }
}
