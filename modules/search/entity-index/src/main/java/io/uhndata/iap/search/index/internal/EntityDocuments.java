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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;

/**
 * Lays an entity out as one block of documents: one for each descendant of the types a listing filters on, at any
 * depth, then the entity's own, last, which is the order a block join reads a block in.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EntityDocuments
{
    private final Set<String> descendantTypes;

    /**
     * Lays entities out with the descendants of some types.
     *
     * @param descendantTypes the node types of the descendants indexed; any other descendant is not
     */
    EntityDocuments(final Set<String> descendantTypes)
    {
        this.descendantTypes = Set.copyOf(descendantTypes);
    }

    /**
     * The block of documents an entity is indexed as.
     *
     * @param entity the entity
     * @return each indexed descendant's document, in document order, then the entity's own
     * @throws RepositoryException if the entity cannot be read
     */
    List<Document> of(final Node entity) throws RepositoryException
    {
        final List<Document> block = new ArrayList<>();
        addDescendants(entity, entity, block);
        block.add(document(entity, entity, IndexFields.ENTITY_KIND));
        return block;
    }

    private void addDescendants(final Node entity, final Node parent, final List<Document> block)
        throws RepositoryException
    {
        for (final NodeIterator children = parent.getNodes(); children.hasNext();) {
            final Node child = children.nextNode();
            if (isIndexed(child)) {
                block.add(document(entity, child, IndexFields.DESCENDANT_KIND));
            }
            addDescendants(entity, child, block);
        }
    }

    private boolean isIndexed(final Node descendant) throws RepositoryException
    {
        for (final String type : this.descendantTypes) {
            if (descendant.isNodeType(type)) {
                return true;
            }
        }
        return false;
    }

    private static Document document(final Node entity, final Node node, final String kind)
        throws RepositoryException
    {
        final Document document = new Document();
        document.add(new StringField(IndexFields.ENTITY_FIELD, entity.getPath(), Store.NO));
        document.add(new StringField(IndexFields.PATH_FIELD, node.getPath(), Store.YES));
        document.add(new StringField(IndexFields.KIND_FIELD, kind, Store.NO));
        for (final String type : typesOf(node)) {
            document.add(new StringField(IndexFields.TYPES_FIELD, type, Store.NO));
        }
        for (final PropertyIterator properties = node.getProperties(); properties.hasNext();) {
            PropertyFields.add(properties.nextProperty(), document);
        }
        return document;
    }

    private static Set<String> typesOf(final Node node) throws RepositoryException
    {
        final Set<String> types = new LinkedHashSet<>();
        final List<NodeType> declared = new ArrayList<>(Arrays.asList(node.getMixinNodeTypes()));
        declared.add(node.getPrimaryNodeType());
        for (final NodeType type : declared) {
            types.add(type.getName());
            Arrays.stream(type.getSupertypes()).map(NodeType::getName).forEach(types::add);
        }
        return types;
    }
}
