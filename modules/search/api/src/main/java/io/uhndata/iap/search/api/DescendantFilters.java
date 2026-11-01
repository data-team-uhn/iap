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
package io.uhndata.iap.search.api;

import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;

/**
 * The conditions one descendant of a listed entity must satisfy. An entity matches only if it has a descendant of
 * the type satisfying all of them; each {@code DescendantFilters} of a listing requires a descendant of its own.
 *
 * @param type the descendant's node type, such as {@code sub:Answer}
 * @param filters the conditions on the descendant's properties; empty if any descendant of the type will do
 * @version $Id$
 * @since 0.1.0
 */
public record DescendantFilters(@NotNull String type, @NotNull List<Filter> filters)
{
    /**
     * Refuses a missing type or condition list, and copies the conditions so the caller cannot change them later.
     */
    public DescendantFilters
    {
        Objects.requireNonNull(type, "Conditions on a descendant need its node type");
        filters = List.copyOf(Objects.requireNonNull(filters, "A descendant's conditions cannot be null"));
    }
}
