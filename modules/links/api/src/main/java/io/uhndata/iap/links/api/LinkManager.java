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
 * Resolves the {@link LinkDefinition link types} defined under {@value #LINK_TYPES_PATH}. The links themselves are
 * read and written through the models. Read any content as {@link Linkable} to list, add or remove its links. Call
 * {@link Link#remove} or {@link io.uhndata.iap.links.models.InternalLink#addBacklink} on a single link.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface LinkManager
{
    /** The path of the homepage that holds the link definitions. */
    String LINK_TYPES_PATH = "/LinkTypes";

    /** The name of the child node that holds the links of a piece of content. */
    String CONTAINER_NAME = "link:links";

    /**
     * A link definition, by name or by path. The definitions are read with the manager's own service user, so a
     * caller without a session of its own can resolve them too. They are cached until {@value #LINK_TYPES_PATH}
     * changes.
     *
     * @param type the name of a definition under {@value #LINK_TYPES_PATH}, such as {@code copiedFrom}, or its
     *            absolute path; a path outside {@value #LINK_TYPES_PATH} names no definition
     * @return the definition; {@code null} if none has that name, or if the service user cannot read the
     *         definitions
     */
    @Nullable
    LinkDefinition getDefinition(@NotNull String type);
}
