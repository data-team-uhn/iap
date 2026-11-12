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

import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.conditions.models.Condition;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.entities.models.EntityPart;

/**
 * The base of the models of what a form holds: a {@link Question}, or a {@link Section} holding more items. Each item
 * may carry one {@link Condition} deciding whether the submitter is asked it.
 *
 * <p>This class is not a Sling Model itself. Each subtype lists {@code FormItem} among its {@code adapters}, so
 * {@code resource.adaptTo(FormItem.class)} returns the model of the resource's own type, with its own fields.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class FormItem extends EntityPart implements Conditionable
{
    /** The {@code sling:resourceType} of a {@code sch:FormItem} node. */
    public static final String RESOURCE_TYPE = "sch/FormItem";

    @Override
    @Nullable
    public Condition getCondition()
    {
        return this.getChild("cond:condition", Condition.class);
    }
}
