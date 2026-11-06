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
 * What a reader may be told about a path that a deletion took away.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface DeletedPathDisclosure
{
    /**
     * What this reader may be told about the requested path.
     *
     * @param request the request that failed with a 404, whose resolver decides how much may be disclosed
     * @param requestedPath the absolute path that was asked for, possibly with selectors and an extension
     * @return what to tell the reader; {@code null} if no deletion took the path away, if the archive cannot be
     *         read, or if this reader may be told nothing
     */
    @Nullable
    Disclosure describe(@NotNull SlingJakartaHttpServletRequest request, @NotNull String requestedPath);

    /**
     * What a reader may learn about one deletion. The page chooses the words.
     *
     * @param deletedAt when the path was deleted, as an ISO-8601 timestamp
     * @param deletedBy the user id of the user who deleted it; {@code null} unless the reader can read the archive
     *            entry
     * @param entryUrl the console route of the archive entry's page; {@code null} unless the reader can read the
     *            entry
     * @version $Id$
     * @since 0.1.0
     */
    record Disclosure(@NotNull String deletedAt, @Nullable String deletedBy, @Nullable String entryUrl)
    {
    }
}
