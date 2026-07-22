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

import io.uhndata.iap.entities.models.EntityPart;

/**
 * The abstract base shared by everything that may appear in the body of a form: a single {@link Question}, or a
 * {@link Section} grouping more items. Corresponds to the {@code sch:FormItem} node type, which mixes in
 * {@code sch:Conditionable} (see {@link Conditionable}) so every item, of any subtype, may carry a single condition
 * controlling whether it is shown to the submitter. Unlike {@link io.uhndata.iap.entities.models.Entity} or
 * {@link Requirement}, this class is deliberately not itself a registered Sling Model (no {@code @Model}
 * annotation): each subtype instead declares {@code adapters = FormItem.class} on its own {@code @Model}, so
 * {@code resource.adaptTo(FormItem.class)} dispatches to the actual concrete subtype (via Sling Models'
 * {@code ResourceTypeBasedResourcePicker}), instead of yielding a generic {@code FormItem} instance lacking a
 * subtype's own fields. This lets a new item type plug in later without any change here or to the callers that ask
 * for "the items" of a section or form, at the cost of {@code FormItem} no longer being directly adaptable to on
 * its own.
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class FormItem extends EntityPart implements Conditionable
{
    /** The {@code sling:resourceType} of a {@code sch:FormItem} node. */
    public static final String RESOURCE_TYPE = "sch/FormItem";

    @Override
    public Condition getCondition()
    {
        return this.getChild("sch:condition", Condition.class);
    }
}
