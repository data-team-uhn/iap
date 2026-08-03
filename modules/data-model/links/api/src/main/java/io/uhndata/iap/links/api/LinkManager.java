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
package io.uhndata.iap.links.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.links.models.Link;
import io.uhndata.iap.links.models.LinkDefinition;
import io.uhndata.iap.links.models.Linkable;

/**
 * The link vocabulary service, resolving the {@link LinkDefinition link definitions} stored under
 * {@value #LINK_TYPES_PATH}. The operations on the links themselves live on the models: view any content model as
 * {@link Linkable} to list, add, or remove its links, and use {@link Link#remove} or
 * {@link io.uhndata.iap.links.models.InternalLink#addBacklink} on an individual link.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface LinkManager
{
    /** The repository location where link definitions are stored. */
    String LINK_TYPES_PATH = "/LinkTypes";

    /** The child node name where the links of a piece of content are stored. */
    String CONTAINER_NAME = "iap:links";

    /**
     * Resolve a link definition. The definitions are world-readable platform vocabulary, read with the manager's
     * own service user — so callers without a session of their own can still resolve them — and cached until
     * {@value #LINK_TYPES_PATH} changes.
     *
     * @param type the name of a definition under {@value #LINK_TYPES_PATH}, or the absolute path of one; any path
     *            outside {@value #LINK_TYPES_PATH} yields nothing
     * @return a link definition, or {@code null} if there is no such definition
     */
    @Nullable
    LinkDefinition getDefinition(@NotNull String type);
}
