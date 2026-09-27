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
package io.uhndata.iap.workflows.internal;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The built-in service task changing content: it applies the event's {@code patch}, one JSON object, to what the
 * task acts on, the resource the execution created or else the target. A key left out is left alone, {@code null}
 * or blank removes the property, anything else is its new value. Only the fields the activity lists, and the node's
 * type declares, may change (see {@link ContentFields}); the whole patch is checked before anything is written, so
 * a refused patch changes nothing.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class UpdateContentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "updateContent";

    /** The payload entry holding the changes. */
    static final String PATCH_PARAMETER = "patch";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final List<ContentFields.Description> described = ContentFields.describedBy(context.getActivity());
        if (described.isEmpty()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " must list the " + ContentFields.FIELDS + " a patch may change");
        }
        final Resource host = ExecutionHost.of(context);
        final Node node = Objects.requireNonNull(host.adaptTo(Node.class), "Content is stored in a JCR repository");
        try {
            final List<ContentFields.Field> editable = ContentFields.editable(described, node);
            final Map<ContentFields.Field, Object> changes = new LinkedHashMap<>();
            for (final Map.Entry<String, JsonValue> entry : patch(context).entrySet()) {
                final ContentFields.Field field = editable.stream()
                    .filter(candidate -> candidate.name().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new InvalidPayloadException(entry.getKey() + " cannot be edited here"));
                changes.put(field, value(context, field, entry.getValue()));
            }
            VersioningUtils.checkOut(node);
            for (final Map.Entry<ContentFields.Field, Object> change : changes.entrySet()) {
                write(node, change.getKey(), change.getValue());
            }
        } catch (final RepositoryException e) {
            throw new PersistenceException("Cannot update " + host.getPath() + ": " + e.getMessage(), e);
        }
    }

    /**
     * The event's patch.
     *
     * @param context the executing task's context
     * @return the changes, by field name
     * @throws InvalidPayloadException when there is no patch, or it is not a JSON object
     */
    private static JsonObject patch(final WorkflowTaskContext context) throws InvalidPayloadException
    {
        final Object patch = context.getEvent().get(PATCH_PARAMETER);
        if (!(patch instanceof String)) {
            throw new InvalidPayloadException("A patch is required");
        }
        try (JsonReader reader = Json.createReader(new StringReader((String) patch))) {
            return reader.readObject();
        } catch (final JsonException | IllegalStateException e) {
            throw new InvalidPayloadException("The patch must be a JSON object", e);
        }
    }

    /**
     * The value a patch sets a field to.
     *
     * @param context the executing task's context
     * @param field the field
     * @param value what the patch gives
     * @return the text or the referenced node, or {@code null} to remove the field
     * @throws InvalidPayloadException when the value is not one the field can hold
     */
    private static Object value(final WorkflowTaskContext context, final ContentFields.Field field,
        final JsonValue value) throws InvalidPayloadException
    {
        if (value.getValueType() != JsonValue.ValueType.NULL && !(value instanceof JsonString)) {
            throw new InvalidPayloadException(field.name() + " must be a string");
        }
        final String text = value instanceof JsonString ? ((JsonString) value).getString().trim() : "";
        if (text.isEmpty()) {
            if (field.mandatory()) {
                throw new InvalidPayloadException(field.name() + " cannot be empty");
            }
            return null;
        }
        return field.reference() ? referenced(context, field, text) : text;
    }

    /**
     * The node a reference field is pointed at.
     *
     * @param context the executing task's context
     * @param field the reference field
     * @param path the path the patch gives
     * @return the node at that path
     * @throws InvalidPayloadException when there is none, or not of the type the field accepts
     */
    private static Node referenced(final WorkflowTaskContext context, final ContentFields.Field field,
        final String path) throws InvalidPayloadException
    {
        final Resource referenced = context.getResourceResolver().getResource(path);
        final Node node = referenced == null ? null : referenced.adaptTo(Node.class);
        if (node == null || field.referenceType() != null && !referenced.isResourceType(field.referenceType())) {
            throw new InvalidPayloadException("There is nothing " + field.name() + " can point to at " + path);
        }
        return node;
    }

    /**
     * Writes one change.
     *
     * @param node the node changed
     * @param field the field
     * @param value the text or the referenced node, or {@code null} to remove the field
     * @throws RepositoryException when it cannot be written
     */
    private static void write(final Node node, final ContentFields.Field field, final Object value)
        throws RepositoryException
    {
        if (value == null) {
            if (node.hasProperty(field.name())) {
                node.getProperty(field.name()).remove();
            }
        } else if (value instanceof Node) {
            node.setProperty(field.name(), (Node) value);
        } else {
            node.setProperty(field.name(), (String) value);
        }
    }
}
