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
package io.uhndata.iap.search.index.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.lang3.StringUtils;

/**
 * Finds the entities under a homepage, wherever they are filed below it: directly, or down the levels of a prefix
 * tree. An entity is the nearest node of the entity type; nothing below one is another entity.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EntityLocator
{
    private final Session session;

    private final String homepage;

    private final String entityType;

    /**
     * Finds the entities of a type under a homepage.
     *
     * @param session the session reading the entities
     * @param homepage the path the entities are filed under
     * @param entityType the node type of an entity
     */
    EntityLocator(final Session session, final String homepage, final String entityType)
    {
        this.session = session;
        this.homepage = homepage;
        this.entityType = entityType;
    }

    /**
     * The entity a path is part of, as the repository now stands: the path may be gone, in which case the entity it
     * was part of is, if it is still there.
     *
     * @param path the path of a node below the homepage, which may no longer exist
     * @return the entity, or empty if the path is not part of one
     * @throws RepositoryException if the repository cannot be read
     */
    Optional<Node> enclosing(final String path) throws RepositoryException
    {
        for (Node node = nearestExisting(path); isBelowHomepage(node); node = node.getParent()) {
            if (node.isNodeType(this.entityType)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    /**
     * The paths of the entities at or below a node.
     *
     * @param path the path of a node
     * @return the paths of the entities, in document order, none if the node does not exist
     * @throws RepositoryException if the repository cannot be read
     */
    List<String> under(final String path) throws RepositoryException
    {
        final List<String> entities = new ArrayList<>();
        if (this.session.nodeExists(path)) {
            collect(this.session.getNode(path), entities);
        }
        return entities;
    }

    /**
     * Whether an entity is at a path.
     *
     * @param path a path, which may not exist
     * @return {@code true} if the node there is an entity
     * @throws RepositoryException if the repository cannot be read
     */
    boolean isEntity(final String path) throws RepositoryException
    {
        return this.session.nodeExists(path) && this.session.getNode(path).isNodeType(this.entityType);
    }

    /**
     * The entity at a path.
     *
     * @param path the path of an entity
     * @return the entity
     * @throws RepositoryException if there is none there, or the repository cannot be read
     */
    Node get(final String path) throws RepositoryException
    {
        return this.session.getNode(path);
    }

    private void collect(final Node node, final List<String> entities) throws RepositoryException
    {
        if (node.isNodeType(this.entityType)) {
            entities.add(node.getPath());
            return;
        }
        for (final NodeIterator children = node.getNodes(); children.hasNext();) {
            collect(children.nextNode(), entities);
        }
    }

    private Node nearestExisting(final String path) throws RepositoryException
    {
        String existing = path;
        while (!this.session.nodeExists(existing)) {
            existing = StringUtils.defaultIfEmpty(StringUtils.substringBeforeLast(existing, "/"), "/");
        }
        return this.session.getNode(existing);
    }

    private boolean isBelowHomepage(final Node node) throws RepositoryException
    {
        return node.getPath().startsWith(this.homepage + "/");
    }
}
