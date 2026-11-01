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
import org.jetbrains.annotations.Nullable;

/**
 * Which entities a pagination request asks for, and in what order: the entities of one type under one homepage,
 * restricted by conditions on themselves and on their descendants, and by a full text search.
 *
 * @param scopePath the absolute path of the homepage the entities are listed under, such as {@code /Submissions}
 * @param entityType the node type of the listed entities, such as {@code sub:Submission}
 * @param filters the conditions on the entities' own properties; empty if there are none
 * @param descendants the conditions on descendants, each requiring a descendant of its own; empty if there are none
 * @param fullText the text the entities must contain; {@code null} if the request searches no text
 * @param sortBy the property of the entities to order them by, such as {@code jcr:created}
 * @param descending {@code true} to list the highest values of {@code sortBy} first
 * @version $Id$
 * @since 0.1.0
 */
public record EntityListing(@NotNull String scopePath, @NotNull String entityType, @NotNull List<Filter> filters,
    @NotNull List<DescendantFilters> descendants, @Nullable String fullText, @NotNull String sortBy,
    boolean descending)
{
    /**
     * Refuses a missing scope, type, condition list or order, and copies the lists so the caller cannot change them
     * later.
     */
    public EntityListing
    {
        Objects.requireNonNull(scopePath, "A listing needs the homepage it lists");
        Objects.requireNonNull(entityType, "A listing needs the type of the entities it lists");
        Objects.requireNonNull(sortBy, "A listing needs the property it is ordered by");
        filters = List.copyOf(Objects.requireNonNull(filters, "A listing's conditions cannot be null"));
        descendants = List.copyOf(Objects.requireNonNull(descendants, "A listing's descendants cannot be null"));
    }
}
