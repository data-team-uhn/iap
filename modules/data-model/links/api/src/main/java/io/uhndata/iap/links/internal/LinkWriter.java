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
package io.uhndata.iap.links.internal;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.links.models.Link;
import io.uhndata.iap.links.models.ResourceLink;

/**
 * The internal service backing the write behavior offered by the {@link Link} models,
 * {@link Link#remove(boolean)} and {@link ResourceLink#addBacklink()}. It works on the models' raw link node
 * resources, which the models pass in themselves, so this interface must stay in this non-exported package: the
 * models are the only public face of a link, and the resources they wrap are not to be reachable through the
 * public API.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface LinkWriter
{
    /**
     * Create the reverse of a link, if its definition declares a backlink and the reverse doesn't exist yet. The
     * reverse is created in memory through the link's own resolver, only when that session may write to the
     * linked resource, and committing it stays the caller's responsibility.
     *
     * @param link an existing link node
     * @return {@code true} if the reverse link now exists in the link's session, {@code false} if the resource is
     *         not a resource link, there is nothing to create, or the session may not create it
     */
    boolean addBacklink(@NotNull Resource link);

    /**
     * Delete a link, in memory: committing the removal is the caller's responsibility.
     *
     * @param link an existing link node
     * @param removeBacklink whether the reverse link, if any, should be deleted too; the reverse is only deleted
     *            when the link's own session may write to the linked resource
     * @return {@code true} if the link was deleted, {@code false} if the resource is not a link or the deletion
     *         failed
     */
    boolean remove(@NotNull Resource link, boolean removeBacklink);
}
