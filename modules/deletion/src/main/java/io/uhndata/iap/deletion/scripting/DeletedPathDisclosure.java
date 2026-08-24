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
package io.uhndata.iap.deletion.scripting;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Answers what a given reader may be told about a path that a deletion took away.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface DeletedPathDisclosure
{
    /**
     * What this reader may be told about the requested path.
     *
     * @param request the request that 404ed, whose own session decides how much may be disclosed
     * @param requestedPath the absolute repository path that was asked for
     * @return what to say, or {@code null} if no deletion accounts for the path — including when the archive
     *         cannot be consulted at all, since a reader cannot be told anything either way
     */
    @Nullable
    Disclosure describe(@NotNull SlingJakartaHttpServletRequest request, @NotNull String requestedPath);

    /**
     * What a reader is allowed to learn about one deletion. Words are left to the page — this says what happened,
     * not how to phrase it, and carries {@code null} for each fact this reader is not entitled to.
     *
     * @param deletedAt when the path was deleted, ISO-8601; always present
     * @param deletedBy who deleted it, only for a reader who can read the archive entry
     * @param entryUrl where to look at the entry, only for a reader who can read it
     * @version $Id$
     * @since 0.1.0
     */
    record Disclosure(@NotNull String deletedAt, @Nullable String deletedBy, @Nullable String entryUrl)
    {
    }
}
