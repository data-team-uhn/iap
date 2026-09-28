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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The built-in service task changing content: it applies the event's {@code patch}, one JSON object, to what the
 * task acts on, the resource the execution created or else the target. A key left out is left alone, {@code null}
 * or blank removes the property, anything else is its new value: text, a number, true or false, the path of the
 * node a reference points at, or a list of those for a field holding several values. Only the fields the activity
 * lists, and the node's type declares, may change (see {@link ContentFields}). A field whose {@code appliesWhen}
 * does not hold once the patch is applied cannot be set, and loses the value it had; a mandatory one that applies
 * must have a value, which content just created only has if the patch gives one. The whole patch is checked
 * before anything is written, so a refused patch changes nothing.
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
        final Node node = Nodes.of(host);
        try {
            final List<ContentFields.Field> editable = ContentFields.editable(described, node);
            final ValueFactory factory = node.getSession().getValueFactory();
            final Map<ContentFields.Field, List<Value>> changes = new LinkedHashMap<>();
            for (final Map.Entry<String, JsonValue> entry : patch(context).entrySet()) {
                final ContentFields.Field field = editable.stream()
                    .filter(candidate -> candidate.name().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new InvalidPayloadException(entry.getKey() + " cannot be edited here"));
                changes.put(field, PatchValues.of(context, factory, field, entry.getValue()));
            }
            holdToApplicability(node, editable, changes);
            VersionableContent.checkOut(node);
            for (final Map.Entry<ContentFields.Field, List<Value>> change : changes.entrySet()) {
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
    static JsonObject patch(final WorkflowTaskContext context) throws InvalidPayloadException
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
     * Holds a patch to what applies once it is applied: a field that does not apply cannot be set, and loses the value
     * it had unless it is mandatory; a mandatory one that applies must have a value.
     *
     * @param node the node changed
     * @param editable the fields that may change on it
     * @param changes the changes the patch makes, to which the removals are added
     * @throws InvalidPayloadException when the patch sets a field that does not apply, or leaves a mandatory one empty
     * @throws RepositoryException when the node cannot be read
     */
    private static void holdToApplicability(final Node node, final List<ContentFields.Field> editable,
        final Map<ContentFields.Field, List<Value>> changes) throws InvalidPayloadException, RepositoryException
    {
        for (final ContentFields.Field field : editable) {
            if (!ContentFields.applies(field, dependency(node, field, changes))) {
                if (!changes.getOrDefault(field, List.of()).isEmpty()) {
                    throw new InvalidPayloadException(field.name() + " does not apply here");
                }
                if (!field.mandatory()) {
                    changes.put(field, List.of());
                }
            } else if (field.mandatory() && !changes.containsKey(field) && !node.hasProperty(field.name())) {
                // Content just created has none of its mandatory fields yet
                throw new InvalidPayloadException(field.name() + " cannot be empty");
            }
        }
    }

    /**
     * The values, as text, of the property a field depends on, once the patch is applied.
     *
     * @param node the node changed
     * @param field the field
     * @param changes the changes the patch makes
     * @return the values, none when the field depends on nothing or the property is not set
     * @throws RepositoryException when the stored values cannot be read
     */
    private static List<String> dependency(final Node node, final ContentFields.Field field,
        final Map<ContentFields.Field, List<Value>> changes) throws RepositoryException
    {
        final ContentFields.Applicability applicability = field.description().appliesWhen();
        final String name = applicability == null ? null : applicability.property();
        if (name == null) {
            return List.of();
        }
        final Optional<List<Value>> changed = changes.entrySet().stream()
            .filter(change -> change.getKey().name().equals(name))
            .map(Map.Entry::getValue)
            .findFirst();
        final List<Value> values = new ArrayList<>();
        if (changed.isPresent()) {
            values.addAll(changed.get());
        } else if (node.hasProperty(name)) {
            final Property stored = node.getProperty(name);
            values.addAll(stored.isMultiple() ? List.of(stored.getValues()) : List.of(stored.getValue()));
        }
        final List<String> texts = new ArrayList<>();
        for (final Value value : values) {
            texts.add(value.getString());
        }
        return texts;
    }

    /**
     * Writes one change.
     *
     * @param node the node changed
     * @param field the field
     * @param values its new values, none to remove it
     * @throws RepositoryException when it cannot be written
     */
    private static void write(final Node node, final ContentFields.Field field, final List<Value> values)
        throws RepositoryException
    {
        if (values.isEmpty()) {
            if (node.hasProperty(field.name())) {
                node.getProperty(field.name()).remove();
            }
        } else if (field.multiple()) {
            node.setProperty(field.name(), values.toArray(Value[]::new));
        } else {
            node.setProperty(field.name(), values.get(0));
        }
    }
}
