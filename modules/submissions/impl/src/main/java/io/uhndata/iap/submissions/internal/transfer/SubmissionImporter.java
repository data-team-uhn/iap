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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.ValueFormatException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.utils.PrefixTree;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

/**
 * Rebuilds a submission another instance sent, from its {@link TransferFormat} manifest, under the same name it had
 * there. Nothing is saved: the caller's session holds the new submission until it commits.
 *
 * <p>
 * References are pointed at whatever is at their path here. One that should point at something that is not here
 * refuses the whole submission, unless it is weak: a weak reference may outlive its target anyway, so it is dropped.
 * A user is whoever has their email here, and keeps their email when nobody does. Each node keeps the date it was
 * created there in {@code originalCreated}, where its type allows one.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SubmissionImporter.class)
public class SubmissionImporter
{
    /** The node type of the prefix tree's buckets, which hold no data of their own. */
    private static final String BUCKET_TYPE = "sling:Folder";

    /** What would make a name a path, or not a name at all. */
    private static final Pattern NOT_A_NAME = Pattern.compile("[/\\[\\]|*]|^\\.{1,2}$|^$");

    /**
     * Where the binaries a manifest names are read from.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface Parts
    {
        /**
         * Opens a part for reading. It is closed once read.
         *
         * @param name the part's name, as the manifest gives it
         * @return its content, or {@code null} if no such part was sent
         * @throws IOException if the part cannot be read
         */
        InputStream open(String name) throws IOException;
    }

    /**
     * Rebuilds a submission.
     *
     * @param home where submissions are kept here
     * @param manifest the submission, as {@link TransferFormat} describes
     * @param parts the binaries the manifest names
     * @return the new submission, not yet saved
     * @throws InvalidPayloadException when the manifest is malformed, does not fit this instance's node types, or
     *     names a part that was not sent
     * @throws InvalidStateException when a submission by that name is already here, or something it points at is not
     * @throws WorkflowFailedException when the repository fails, or a part cannot be read
     * @throws PersistenceException when the bucket for the submission cannot be made
     */
    public Resource importInto(final Resource home, final JsonObject manifest, final Parts parts)
        throws WorkflowException, PersistenceException
    {
        try {
            if (manifest.getInt(TransferFormat.VERSION_KEY, 0) != TransferFormat.FORMAT_VERSION) {
                throw new InvalidPayloadException("This instance only reads transfer format version "
                    + TransferFormat.FORMAT_VERSION);
            }
            final JsonObject root = object(manifest, TransferFormat.ROOT_KEY);
            final String name = name(root);
            if (home.getResourceResolver().getResource(PrefixTree.pathFor(home.getPath(), name)) != null) {
                throw new InvalidStateException("A submission named " + name + " is already here");
            }
            final Node bucket = Objects.requireNonNull(
                PrefixTree.pendingBucketFor(home, name, BUCKET_TYPE).adaptTo(Node.class), "A bucket is a node");
            final Import run = new Import(bucket.getSession(), parts);
            final Node submission = run.node(bucket, root);
            run.references(submission);
            return Objects.requireNonNull(home.getResourceResolver().getResource(submission.getPath()),
                "The submission was just made");
        } catch (final ClassCastException | IllegalArgumentException e) {
            throw new InvalidPayloadException("The submission's manifest is malformed", e);
        } catch (final ConstraintViolationException | ValueFormatException | NoSuchNodeTypeException e) {
            throw new InvalidPayloadException("The submission does not fit this instance: " + e.getMessage(), e);
        } catch (final RepositoryException e) {
            throw new WorkflowFailedException("Could not rebuild the submission", e);
        }
    }

    /**
     * A node's name, refused when it is not a name at all: one that is a path would put the node somewhere else.
     *
     * @param node a node of a manifest
     * @return its name
     * @throws InvalidPayloadException when it has none, or one that is not a name
     */
    static String name(final JsonObject node) throws InvalidPayloadException
    {
        final String name = text(node, TransferFormat.NAME_KEY);
        if (NOT_A_NAME.matcher(name).find()) {
            throw new InvalidPayloadException("\"" + name + "\" is not a node name");
        }
        return name;
    }

    /**
     * An object a manifest must have.
     *
     * @param json the object holding it
     * @param key its key
     * @return the object
     * @throws InvalidPayloadException when there is none, or it is not an object
     */
    static JsonObject object(final JsonObject json, final String key) throws InvalidPayloadException
    {
        if (json.get(key) instanceof JsonObject object) {
            return object;
        }
        throw missing(key);
    }

    private static JsonArray array(final JsonObject json, final String key) throws InvalidPayloadException
    {
        if (json.get(key) instanceof JsonArray array) {
            return array;
        }
        throw missing(key);
    }

    private static String text(final JsonObject json, final String key) throws InvalidPayloadException
    {
        if (json.get(key) instanceof JsonString text) {
            return text.getString();
        }
        throw missing(key);
    }

    private static InvalidPayloadException missing(final String key)
    {
        return new InvalidPayloadException("The submission's manifest has no valid \"" + key + "\" where one belongs");
    }

    /**
     * One import in progress.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class Import
    {
        private final Session session;

        private final ValueFactory values;

        private final Parts parts;

        private final UserDirectory users;

        private final List<PendingReference> references = new ArrayList<>();

        Import(final Session session, final Parts parts) throws RepositoryException
        {
            this.session = session;
            this.values = session.getValueFactory();
            this.parts = parts;
            this.users = new UserDirectory(session);
        }

        Node node(final Node parent, final JsonObject json) throws WorkflowException, RepositoryException
        {
            final String name = name(json);
            // A child its parent was given on creation, such as an autocreated one, is filled in
            final Node node = parent.hasNode(name) ? parent.getNode(name)
                : parent.addNode(name, text(json, TransferFormat.TYPE_KEY));
            for (final JsonValue mixin : array(json, TransferFormat.MIXINS_KEY)) {
                final String type = ((JsonString) mixin).getString();
                if (!node.isNodeType(type)) {
                    node.addMixin(type);
                }
            }
            if (json.containsKey(TransferFormat.CREATED_KEY)) {
                created(node, text(json, TransferFormat.CREATED_KEY));
            }
            for (final Map.Entry<String, JsonValue> property : object(json, TransferFormat.PROPERTIES_KEY)
                .entrySet()) {
                property(node, property.getKey(), (JsonObject) property.getValue());
            }
            for (final JsonValue child : array(json, TransferFormat.CHILDREN_KEY)) {
                node(node, (JsonObject) child);
            }
            return node;
        }

        private void created(final Node node, final String date) throws RepositoryException
        {
            final Value value = this.values.createValue(date, PropertyType.DATE);
            // A type that takes no properties of its own keeps only the date it arrived
            if (NodeTypes.accepts(node, TransferFormat.ORIGINAL_CREATED_PROPERTY, value)) {
                node.setProperty(TransferFormat.ORIGINAL_CREATED_PROPERTY, value);
            }
        }

        private void property(final Node node, final String name, final JsonObject json)
            throws WorkflowException, RepositoryException
        {
            final int type = PropertyType.valueFromName(text(json, TransferFormat.TYPE_KEY));
            final boolean multiple = json.containsKey(TransferFormat.VALUES_KEY);
            final List<String> encoded = multiple
                ? array(json, TransferFormat.VALUES_KEY).getValuesAs(JsonString::getString)
                : List.of(text(json, TransferFormat.VALUE_KEY));
            if (type == PropertyType.REFERENCE || type == PropertyType.WEAKREFERENCE) {
                this.references.add(new PendingReference(node, name, type, multiple, encoded));
                return;
            }
            if (json.getBoolean(TransferFormat.EMAIL_KEY, false)) {
                final String email = encoded.get(0);
                final String id = this.users.idOf(email);
                node.setProperty(name, id == null ? email : id);
                return;
            }
            final Value[] decoded = new Value[encoded.size()];
            for (int i = 0; i < decoded.length; i++) {
                decoded[i] = type == PropertyType.BINARY ? binary(encoded.get(i))
                    : this.values.createValue(encoded.get(i), type);
            }
            if (multiple) {
                node.setProperty(name, decoded, type);
            } else {
                node.setProperty(name, decoded[0]);
            }
        }

        private Value binary(final String part) throws WorkflowException, RepositoryException
        {
            try (InputStream content = this.parts.open(part)) {
                if (content == null) {
                    throw new InvalidPayloadException("The part " + part + " the manifest names was not sent");
                }
                return this.values.createValue(this.values.createBinary(content));
            } catch (final IOException e) {
                throw new WorkflowFailedException("Could not read the part " + part, e);
            }
        }

        /**
         * Points the references at what is now at their paths, once everything they may point at inside the
         * submission exists.
         */
        void references(final Node submission) throws WorkflowException, RepositoryException
        {
            for (final PendingReference reference : this.references) {
                final boolean weak = reference.type() == PropertyType.WEAKREFERENCE;
                final List<Value> pointed = new ArrayList<>();
                for (final String path : reference.paths()) {
                    final String absolute = absolute(submission, path);
                    if (this.session.nodeExists(absolute)) {
                        pointed.add(this.values.createValue(this.session.getNode(absolute), weak));
                    } else if (!weak) {
                        throw new InvalidStateException(reference.name() + " points at " + absolute
                            + ", which is not here");
                    }
                }
                if (reference.multiple()) {
                    reference.node().setProperty(reference.name(), pointed.toArray(Value[]::new), reference.type());
                } else if (!pointed.isEmpty()) {
                    reference.node().setProperty(reference.name(), pointed.get(0));
                }
            }
        }

        private static String absolute(final Node submission, final String path) throws RepositoryException
        {
            if (TransferFormat.SUBMISSION_ITSELF.equals(path)) {
                return submission.getPath();
            }
            return path.startsWith("/") ? path : submission.getPath() + "/" + path;
        }
    }

    /**
     * A reference found during the import, set once everything it may point at exists.
     *
     * @param node the node it goes on
     * @param name the property's name
     * @param type whether it is a strong or a weak reference
     * @param multiple whether the property is multi-valued
     * @param paths what it points at, as the manifest gives it
     * @version $Id$
     * @since 0.1.0
     */
    private record PendingReference(Node node, String name, int type, boolean multiple, List<String> paths)
    {
    }
}
