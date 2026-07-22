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

/**
 * Implemented by every model that can be made conditionally enabled, independently of its own supertype chain, such
 * as {@link FormItem} and {@link Requirement}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface Conditionable
{
    /**
     * The condition controlling whether this node is enabled, adapted to its own specific model regardless of
     * whether it is a {@link SingleCondition}, a {@link ConditionGroup}, or any future {@link Condition} subtype.
     *
     * @return a condition, or {@code null} if this node is always enabled
     */
    Condition getCondition();
}
