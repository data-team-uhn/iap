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
 * The abstract base shared by a single condition ({@link Conditional}) and a composite of conditions
 * ({@link ConditionalGroup}), corresponding to the {@code sch:Condition} node type. Unlike
 * {@link io.uhndata.iap.entities.models.Entity} or {@link Requirement}, this class is deliberately not itself a
 * registered Sling Model (no {@code @Model} annotation): each subtype instead declares
 * {@code adapters = Condition.class} on its own {@code @Model}, so {@code resource.adaptTo(Condition.class)}
 * dispatches to the actual concrete subtype (via Sling Models' {@code ResourceTypeBasedResourcePicker}), instead
 * of yielding a generic, field-less {@code Condition} instance. This lets a new condition type plug in later
 * without any change here or to the callers that ask for "the conditions" of a section or requirement, at the cost
 * of {@code Condition} no longer being directly adaptable to on its own.
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class Condition extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sch:Condition} node. */
    public static final String RESOURCE_TYPE = "sch/Condition";
}
