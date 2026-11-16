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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.links.api.LinkManager;
import io.uhndata.iap.links.models.InternalLink;
import io.uhndata.iap.links.models.InternalLinkDefinition;
import io.uhndata.iap.links.models.Link;

/**
 * An internal link that a commit removed, as it was before the commit. It names its source, its destination and its
 * type, which is all it takes to find its reverse.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class RemovedLink
{
    private static final Set<String> LINK_NODE_TYPES = Set.of("link:Link", "link:WeakLink");

    private final String path;

    private final String sourcePath;

    private final String destinationId;

    private final String definitionId;

    /**
     * Describes a removed internal link.
     *
     * @param path the path the link had
     * @param before the link node as it was before the commit
     */
    RemovedLink(final String path, final NodeState before)
    {
        this.path = path;
        // The link sits in its source's links container
        this.sourcePath = ResourceUtil.getParent(path, 2);
        this.destinationId = stringValue(before, InternalLink.REFERENCE_PROPERTY);
        this.definitionId = stringValue(before, Link.TYPE_PROPERTY);
    }

    /**
     * Whether a node is an internal link: a {@code link:Link} or a {@code link:WeakLink}.
     *
     * @param node the node as a commit found it
     * @return {@code true} for an internal link node
     */
    static boolean isLink(final NodeState node)
    {
        return Optional.ofNullable(node.getName("jcr:primaryType"))
            .filter(LINK_NODE_TYPES::contains)
            .isPresent();
    }

    /**
     * The path the link had, such as {@code /Things/a/link:links/<name>}.
     *
     * @return an absolute path
     */
    String getPath()
    {
        return this.path;
    }

    /**
     * The reverses this link leaves without a counterpart: links under the destination, pointing back at the source,
     * of a type that pairs with this link's, which no other link of the source pairs with. Labels do not count, as
     * for {@link InternalLink#isReverseOf}.
     *
     * @param resolver the resolver to read the current content with
     * @return the reverse link nodes; empty if the destination holds none, or if the source or destination is gone
     */
    List<Resource> findReverses(final ResourceResolver resolver)
    {
        return Optional.ofNullable(resolver.getResource(this.sourcePath))
            .map(source -> source.adaptTo(Content.class))
            .map(source -> source.getReference(this.destinationId, Content.class))
            .map(destination -> resolver.getResource(destination.getPath() + "/" + LinkManager.CONTAINER_NAME))
            .stream()
            .flatMap(container -> StreamSupport.stream(container.getChildren().spliterator(), false))
            .filter(this::isReverse)
            .collect(Collectors.toList());
    }

    private boolean isReverse(final Resource candidate)
    {
        final Link link = Link.toLink(candidate);
        return link instanceof InternalLink && this.isReversedBy((InternalLink) link);
    }

    private boolean isReversedBy(final InternalLink candidate)
    {
        return this.isPointedAtBy(candidate)
            && this.pairsWithTypeOf(candidate)
            && candidate.getBacklink() == null;
    }

    private boolean isPointedAtBy(final InternalLink candidate)
    {
        return Optional.ofNullable(candidate.getDestination())
            .map(Content::getPath)
            .filter(this.sourcePath::equals)
            .isPresent();
    }

    private boolean pairsWithTypeOf(final InternalLink candidate)
    {
        return Optional.ofNullable(candidate.getReference(this.definitionId, InternalLinkDefinition.class))
            .flatMap(removedType -> Optional.ofNullable(candidate.getDefinition()).filter(removedType::pairsWith))
            .isPresent();
    }

    private static String stringValue(final NodeState node, final String name)
    {
        return Optional.ofNullable(node.getProperty(name))
            .map(property -> property.getValue(Type.STRING))
            .orElse(null);
    }
}
