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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.links.models.ExternalLink;
import io.uhndata.iap.links.models.InternalLink;
import io.uhndata.iap.links.models.Link;
import io.uhndata.iap.links.models.Linkable;

/**
 * The link operations behind the links models: {@link Linkable} for a piece of content, {@link Link#remove} and
 * {@link InternalLink#addBacklink} for a single link. Each model passes in its own resource. This interface stays in
 * a package the bundle does not export, so the public API never hands out a resource.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface LinkOperations
{
    /**
     * All the links a node holds, internal and external.
     *
     * @param source the node holding the links
     * @return the links; empty if the node holds none
     */
    @NotNull
    List<Link> getLinks(@NotNull Resource source);

    /**
     * The links of one type that a node holds.
     *
     * @param source the node holding the links
     * @param type the name or path of the link definition, such as {@code copiedFrom}
     * @return the links; empty if the node holds none of this type, or the type is not defined
     */
    @NotNull
    List<Link> getLinks(@NotNull Resource source, @NotNull String type);

    /**
     * The links that other content holds to a node.
     *
     * @param target the linked node
     * @return the links; empty if none point at it, or if the references cannot be read
     */
    @NotNull
    List<InternalLink> getBacklinks(@NotNull Resource target);

    /**
     * Adds a link from a node to other content, in the node's session. The caller commits it. An existing link of
     * the same type to the same content comes back instead of a duplicate. Its label must match, unless
     * {@code label} is {@code null}.
     *
     * @param source the node to hold the link
     * @param destination the content to link to; it must be referenceable
     * @param type the name or path of the link definition, such as {@code copiedFrom}
     * @param label the link's own label; {@code null} or empty for none
     * @return the new link, or the existing one
     * @throws IllegalArgumentException if the definition is unknown, external or backlink-only, if either end is
     *             not of a required type, or if the destination is not referenceable
     */
    @NotNull
    InternalLink addLink(@NotNull Resource source, @NotNull Content destination, @NotNull String type,
        @Nullable String label);

    /**
     * Adds an external link to a node, in the node's session. The caller commits it. An existing link of the same
     * type with the same value comes back instead of a duplicate. Its label must match, unless {@code label} is
     * {@code null}.
     *
     * @param source the node to hold the link
     * @param type the name or path of the link definition
     * @param value the value naming the target, such as an identifier in another system
     * @param label the link's own label; {@code null} or empty for none
     * @return the new link, or the existing one
     * @throws IllegalArgumentException if the definition is unknown or not external, if the source is not of a
     *             required type, or if the value is blank or does not match the definition's value pattern
     */
    @NotNull
    ExternalLink addExternalLink(@NotNull Resource source, @NotNull String type, @NotNull String value,
        @Nullable String label);

    /**
     * Deletes a node's links of one type, to the given content and with the given label, in the node's session.
     * The caller commits the deletions.
     *
     * @param source the node holding the links
     * @param destination the linked content; {@code null} to match any target, external links included
     * @param type the name or path of the link definition
     * @param label the label to match; {@code null} for any label, empty for links without one
     * @return the number of links deleted
     */
    int removeLinks(@NotNull Resource source, @Nullable Content destination, @NotNull String type,
        @Nullable String label);

    /**
     * Adds the reverse of a link, if its definition names a backlink and the reverse does not exist yet. The
     * reverse goes into the link's session, and only if that session may add nodes under the linked content. The
     * caller commits it.
     *
     * @param link a link node
     * @return {@code true} if the reverse now exists in the link's session; {@code false} if the node is not an
     *         internal link, its definition names no backlink, or the reverse cannot be added in this session
     */
    boolean addBacklink(@NotNull Resource link);

    /**
     * Deletes a link, in the link's session. The caller commits the deletion.
     *
     * @param link a link node
     * @param removeBacklink whether to delete the reverse link too, if the link's session can read it
     * @return {@code true} if the link was deleted; {@code false} if the node is not a link, or the deletion
     *         failed
     */
    boolean remove(@NotNull Resource link, boolean removeBacklink);
}
