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

import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.links.models.InternalLink;
import io.uhndata.iap.links.models.Link;

/**
 * Finds the links pointing at a piece of content, by walking the JCR references held against it. This is the one
 * place raw reference properties are iterated: a link is stored as a reference from the linking resource, so the
 * only way to ask "what points here" is to go through the repository rather than through the content tree.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class BacklinkFinder
{
    private BacklinkFinder()
    {
        // Utility class, not instantiated
    }

    /**
     * The links held by other resources that point at the given one.
     *
     * @param session the session to read the references through
     * @param resource the linked resource
     * @return the links pointing at it, empty if there are none
     * @throws RepositoryException if the references cannot be read
     */
    static List<InternalLink> find(final Session session, final Resource resource)
        throws RepositoryException
    {
        final List<InternalLink> result = new ArrayList<>();
        final Node node = session.getNode(resource.getPath());
        // Hard and weak references are separate indexes in JCR, and a link type may use either
        collect(node.getReferences(InternalLink.REFERENCE_PROPERTY), resource, result);
        collect(node.getWeakReferences(InternalLink.REFERENCE_PROPERTY), resource, result);
        return result;
    }

    private static void collect(final PropertyIterator references, final Resource resource,
        final List<InternalLink> result)
        throws RepositoryException
    {
        while (references.hasNext()) {
            final Property property = references.nextProperty();
            final Resource linkResource = resource.getResourceResolver().getResource(property.getParent().getPath());
            final Link link = linkResource == null ? null : Link.toLink(linkResource);
            if (link instanceof InternalLink) {
                result.add((InternalLink) link);
            }
        }
    }
}
