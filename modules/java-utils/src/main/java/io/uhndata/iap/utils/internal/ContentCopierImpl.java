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
package io.uhndata.iap.utils.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.nodetype.NodeType;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import io.uhndata.iap.utils.copy.ContentCopier;
import io.uhndata.iap.utils.copy.CopyParticipant;

/**
 * The {@link ContentCopier}: a depth-first copy in document order, which sets references once every node they may
 * point at has been copied, and then lets each {@link CopyParticipant} adjust the result.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ContentCopier.class)
public class ContentCopierImpl implements ContentCopier
{
    /** The modification stamps of mix:lastModified, which the copy gets its own of. */
    private static final Set<String> STAMPS = Set.of("jcr:lastModified", "jcr:lastModifiedBy");

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY, fieldOption = FieldOption.UPDATE)
    private final List<CopyParticipant> participants = new CopyOnWriteArrayList<>();

    @Override
    public Map<String, String> copy(final Node source, final Node target, final Set<String> skipped,
        final Map<String, Set<String>> dropped) throws RepositoryException
    {
        return new Copy(List.copyOf(this.participants)).run(source, target, skipped, dropped);
    }

    /**
     * One copy in progress.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class Copy
    {
        private final List<CopyParticipant> participants;

        private final Map<String, String> identifiers = new HashMap<>();

        private final List<Map.Entry<Node, Property>> references = new ArrayList<>();

        /**
         * A copy consulting the given participants.
         *
         * @param participants the participants registered when it starts
         */
        Copy(final List<CopyParticipant> participants)
        {
            this.participants = participants;
        }

        /**
         * Makes the copy: properties and children, then the references found on the way, once everything they may
         * point at exists, and then whatever the participants adjust.
         *
         * @param source the node copied
         * @param target the node receiving the copy
         * @param skipped properties of the source node itself that are not copied
         * @param dropped values left out of multi-valued properties of the source node itself, by property name
         * @return the identifiers of the copied referenceable nodes, each original's mapped to its copy's
         * @throws RepositoryException when the source cannot be read or the copy cannot be written
         */
        Map<String, String> run(final Node source, final Node target, final Set<String> skipped,
            final Map<String, Set<String>> dropped) throws RepositoryException
        {
            this.identifiers.put(source.getIdentifier(), target.getIdentifier());
            properties(source, target, skipped, dropped);
            children(source, target);
            for (final Map.Entry<Node, Property> reference : this.references) {
                reference(reference.getKey(), reference.getValue());
            }
            for (final CopyParticipant participant : this.participants) {
                participant.afterCopy(source, target, this.identifiers);
            }
            return Map.copyOf(this.identifiers);
        }

        /**
         * Copies a node's children, and theirs, in order.
         *
         * @param from the node copied
         * @param to its copy
         * @throws RepositoryException when the copy cannot be written
         */
        private void children(final Node from, final Node to) throws RepositoryException
        {
            final NodeIterator children = from.getNodes();
            while (children.hasNext()) {
                final Node child = children.nextNode();
                if (skips(child)) {
                    continue;
                }
                // A child the copy was given on creation, such as an autocreated one, is filled in
                final Node copy = to.hasNode(child.getName()) ? to.getNode(child.getName())
                    : to.addNode(child.getName(), child.getPrimaryNodeType().getName());
                for (final NodeType mixin : child.getMixinNodeTypes()) {
                    if (!copy.isNodeType(mixin.getName())) {
                        copy.addMixin(mixin.getName());
                    }
                }
                if (child.isNodeType("mix:referenceable")) {
                    this.identifiers.put(child.getIdentifier(), copy.getIdentifier());
                }
                properties(child, copy, Set.of(), Map.of());
                children(child, copy);
            }
        }

        /**
         * Copies a node's properties, keeping references for when their targets have been copied too.
         *
         * @param from the node copied
         * @param to its copy
         * @param skipped properties not copied
         * @param dropped values left out of multi-valued properties, by property name
         * @throws RepositoryException when the copy cannot be written
         */
        private void properties(final Node from, final Node to, final Set<String> skipped,
            final Map<String, Set<String>> dropped) throws RepositoryException
        {
            final PropertyIterator properties = from.getProperties();
            while (properties.hasNext()) {
                final Property property = properties.nextProperty();
                final String name = property.getName();
                if (property.getDefinition().isProtected() || STAMPS.contains(name) || skipped.contains(name)
                    || skips(property)) {
                    continue;
                }
                final int type = property.getType();
                if (type == PropertyType.REFERENCE || type == PropertyType.WEAKREFERENCE) {
                    this.references.add(Map.entry(to, property));
                } else {
                    value(to, property, dropped.getOrDefault(name, Set.of()));
                }
            }
        }

        /**
         * Copies a property that is not a reference.
         *
         * @param to the copy
         * @param property the original property
         * @param dropped values left out, if it is multi-valued
         * @throws RepositoryException when the property cannot be written
         */
        private static void value(final Node to, final Property property, final Set<String> dropped)
            throws RepositoryException
        {
            if (property.isMultiple()) {
                to.setProperty(property.getName(), kept(property, dropped), property.getType());
            } else {
                to.setProperty(property.getName(), property.getValue());
            }
        }

        /**
         * Sets a reference on a copy, pointing at the copy of what the original pointed at, if it was copied.
         *
         * @param node the copy
         * @param original the original property
         * @throws RepositoryException when the reference cannot be written
         */
        private void reference(final Node node, final Property original) throws RepositoryException
        {
            final ValueFactory factory = node.getSession().getValueFactory();
            final int type = original.getType();
            final Value[] from = original.isMultiple() ? original.getValues() : new Value[] { original.getValue() };
            final Value[] pointed = new Value[from.length];
            for (int i = 0; i < from.length; i++) {
                final String identifier = from[i].getString();
                pointed[i] = factory.createValue(this.identifiers.getOrDefault(identifier, identifier), type);
            }
            if (original.isMultiple()) {
                node.setProperty(original.getName(), pointed, type);
            } else {
                node.setProperty(original.getName(), pointed[0]);
            }
        }

        /**
         * Whether a participant leaves a property out.
         *
         * @param property the property
         * @return whether it is left out
         * @throws RepositoryException when it cannot be read
         */
        private boolean skips(final Property property) throws RepositoryException
        {
            for (final CopyParticipant participant : this.participants) {
                if (participant.skips(property)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether a participant leaves a child out.
         *
         * @param child the child
         * @return whether it is left out
         * @throws RepositoryException when it cannot be read
         */
        private boolean skips(final Node child) throws RepositoryException
        {
            for (final CopyParticipant participant : this.participants) {
                if (participant.skips(child)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The values a multi-valued property keeps.
         *
         * @param property the property
         * @param dropped the values left out
         * @return the remaining values, in order
         * @throws RepositoryException when the values cannot be read
         */
        private static Value[] kept(final Property property, final Set<String> dropped) throws RepositoryException
        {
            final List<Value> kept = new ArrayList<>();
            for (final Value value : property.getValues()) {
                if (!dropped.contains(value.getString())) {
                    kept.add(value);
                }
            }
            return kept.toArray(Value[]::new);
        }
    }
}
