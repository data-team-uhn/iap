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
package io.uhndata.iap.submissions.internal.transfer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.jcr.Binary;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import io.uhndata.iap.utils.copy.CopyParticipant;

/**
 * Writes a submission and everything under it out as a {@link TransferFormat} manifest, for another instance to
 * rebuild.
 *
 * <p>
 * Only what the submission holds travels. What the repository maintains is left out, as are its workflow
 * instances, which the receiver starts afresh, and whatever the modules contributing {@link CopyParticipant}s say a
 * copy leaves out, such as computed tags and links: the same judgement a local copy makes.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SubmissionExporter.class)
public class SubmissionExporter
{
    /** The workflow instances attached to a submission, which belong to the instance that runs them. */
    private static final String WORKFLOW_INSTANCES = "wf:instances";

    private static final String REPOSITORY_CREATED_PROPERTY = "jcr:created";

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY, fieldOption = FieldOption.UPDATE)
    private final List<CopyParticipant> participants = new CopyOnWriteArrayList<>();

    /**
     * Writes a submission out.
     *
     * @param submission the submission
     * @return its manifest, and the binaries the manifest names
     * @throws RepositoryException when the submission cannot be read
     */
    public SubmissionExport export(final Resource submission) throws RepositoryException
    {
        final Node root = Objects.requireNonNull(submission.adaptTo(Node.class), "A submission is a node");
        return new Export(root, List.copyOf(this.participants)).run();
    }

    /**
     * One export in progress.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class Export
    {
        private final Node root;

        private final List<CopyParticipant> participants;

        private final UserDirectory users;

        private final Map<String, Binary> binaries = new LinkedHashMap<>();

        Export(final Node root, final List<CopyParticipant> participants) throws RepositoryException
        {
            this.root = root;
            this.participants = participants;
            this.users = new UserDirectory(root.getSession());
        }

        SubmissionExport run() throws RepositoryException
        {
            return new SubmissionExport(Json.createObjectBuilder()
                .add(TransferFormat.VERSION_KEY, TransferFormat.FORMAT_VERSION)
                .add(TransferFormat.ROOT_KEY, node(this.root))
                .build(), this.binaries);
        }

        private JsonObjectBuilder node(final Node node) throws RepositoryException
        {
            final JsonArrayBuilder mixins = Json.createArrayBuilder();
            for (final NodeType mixin : node.getMixinNodeTypes()) {
                mixins.add(mixin.getName());
            }
            final JsonObjectBuilder json = Json.createObjectBuilder()
                .add(TransferFormat.NAME_KEY, node.getName())
                .add(TransferFormat.TYPE_KEY, node.getPrimaryNodeType().getName())
                .add(TransferFormat.MIXINS_KEY, mixins);
            final String created = created(node);
            if (created != null) {
                json.add(TransferFormat.CREATED_KEY, created);
            }
            return json.add(TransferFormat.PROPERTIES_KEY, properties(node))
                .add(TransferFormat.CHILDREN_KEY, children(node));
        }

        /**
         * When a node was first created: there, if it came from yet another instance, else here.
         */
        private static String created(final Node node) throws RepositoryException
        {
            for (final String name : List.of(TransferFormat.ORIGINAL_CREATED_PROPERTY, REPOSITORY_CREATED_PROPERTY)) {
                if (node.hasProperty(name)) {
                    return node.getProperty(name).getString();
                }
            }
            return null;
        }

        private JsonObjectBuilder properties(final Node node) throws RepositoryException
        {
            final JsonObjectBuilder json = Json.createObjectBuilder();
            final PropertyIterator properties = node.getProperties();
            while (properties.hasNext()) {
                final Property property = properties.nextProperty();
                if (!skips(property)) {
                    final JsonObjectBuilder value = property(property);
                    if (value != null) {
                        json.add(property.getName(), value);
                    }
                }
            }
            if (!node.hasProperty(TransferFormat.CREATED_BY_PROPERTY)
                && node.hasProperty(TransferFormat.WRITTEN_BY_PROPERTY)) {
                // Written outside the engine, so the repository saw the person; unless it was machinery after all,
                // or the node's type has nowhere to say so
                final Value writer = node.getProperty(TransferFormat.WRITTEN_BY_PROPERTY).getValue();
                if (!this.users.isServiceUser(writer.getString())
                    && NodeTypes.accepts(node, TransferFormat.CREATED_BY_PROPERTY, writer)) {
                    json.add(TransferFormat.CREATED_BY_PROPERTY, user(writer.getString()));
                }
            }
            return json;
        }

        private boolean skips(final Property property) throws RepositoryException
        {
            // The original creation date travels as the node's own, rather than as a property
            if (property.getDefinition().isProtected()
                || TransferFormat.ORIGINAL_CREATED_PROPERTY.equals(property.getName())) {
                return true;
            }
            for (final CopyParticipant participant : this.participants) {
                if (participant.skips(property)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * A property as the manifest carries it, or {@code null} when nothing of it is left to carry: a weak
         * reference whose target is gone.
         */
        private JsonObjectBuilder property(final Property property) throws RepositoryException
        {
            final String name = property.getName();
            if (TransferFormat.USER_PROPERTIES.contains(name) && property.getType() == PropertyType.STRING
                && !property.isMultiple()) {
                return user(property.getString());
            }
            final JsonObjectBuilder json = Json.createObjectBuilder()
                .add(TransferFormat.TYPE_KEY, PropertyType.nameFromValue(property.getType()));
            if (!property.isMultiple()) {
                final String value = value(property.getValue());
                return value == null ? null : json.add(TransferFormat.VALUE_KEY, value);
            }
            final JsonArrayBuilder values = Json.createArrayBuilder();
            for (final Value value : property.getValues()) {
                final String encoded = value(value);
                if (encoded != null) {
                    values.add(encoded);
                }
            }
            return json.add(TransferFormat.VALUES_KEY, values);
        }

        private JsonObjectBuilder user(final String id) throws RepositoryException
        {
            final String email = this.users.emailOf(id);
            final JsonObjectBuilder json = Json.createObjectBuilder()
                .add(TransferFormat.TYPE_KEY, PropertyType.TYPENAME_STRING);
            return email == null ? json.add(TransferFormat.VALUE_KEY, id)
                : json.add(TransferFormat.VALUE_KEY, email).add(TransferFormat.EMAIL_KEY, true);
        }

        /**
         * A value as the manifest carries it, or {@code null} for a weak reference whose target is gone.
         */
        private String value(final Value value) throws RepositoryException
        {
            return switch (value.getType()) {
                case PropertyType.REFERENCE, PropertyType.WEAKREFERENCE -> path(value.getString());
                case PropertyType.BINARY -> {
                    final String part = "b" + this.binaries.size();
                    this.binaries.put(part, value.getBinary());
                    yield part;
                }
                default -> value.getString();
            };
        }

        private String path(final String identifier) throws RepositoryException
        {
            final String target;
            try {
                target = this.root.getSession().getNodeByIdentifier(identifier).getPath();
            } catch (final ItemNotFoundException e) {
                // Only a weak reference can outlive its target, and it would not resolve on arrival either
                return null;
            }
            final String rootPath = this.root.getPath();
            if (target.equals(rootPath)) {
                return TransferFormat.SUBMISSION_ITSELF;
            }
            return target.startsWith(rootPath + "/") ? target.substring(rootPath.length() + 1) : target;
        }

        private JsonArrayBuilder children(final Node node) throws RepositoryException
        {
            final JsonArrayBuilder json = Json.createArrayBuilder();
            final NodeIterator children = node.getNodes();
            while (children.hasNext()) {
                final Node child = children.nextNode();
                if (!skips(child)) {
                    json.add(node(child));
                }
            }
            return json;
        }

        private boolean skips(final Node child) throws RepositoryException
        {
            // Protected children, such as an access control policy, are the repository's
            if (child.getDefinition().isProtected() || WORKFLOW_INSTANCES.equals(child.getName())) {
                return true;
            }
            for (final CopyParticipant participant : this.participants) {
                if (participant.skips(child)) {
                    return true;
                }
            }
            return false;
        }
    }
}
