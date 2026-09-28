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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * What the JSON values of a patch become, for {@link UpdateContentHandler}: text, a whole number, a number, true or
 * false, or the path of the node a reference points at, as the field's kind asks, and a list of those for a field
 * holding several values. Blank text counts as no value.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class PatchValues
{
    private PatchValues()
    {
        // Utility class
    }

    /**
     * The values a patch sets a field to.
     *
     * @param context the executing task's context
     * @param factory makes the values
     * @param field the field
     * @param value what the patch gives
     * @return the values, none to remove the field
     * @throws InvalidPayloadException when the value is not one the field can hold
     * @throws RepositoryException when a value cannot be made
     */
    static List<Value> of(final WorkflowTaskContext context, final ValueFactory factory,
        final ContentFields.Field field, final JsonValue value) throws InvalidPayloadException, RepositoryException
    {
        final List<Value> values = new ArrayList<>();
        if (field.multiple() && value instanceof JsonArray) {
            for (final JsonValue item : (JsonArray) value) {
                single(context, factory, field, item).ifPresent(values::add);
            }
        } else if (field.multiple() && value.getValueType() != JsonValue.ValueType.NULL) {
            throw new InvalidPayloadException(field.name() + " must be a list");
        } else if (value.getValueType() != JsonValue.ValueType.NULL) {
            single(context, factory, field, value).ifPresent(values::add);
        }
        if (values.isEmpty() && field.mandatory()) {
            throw new InvalidPayloadException(field.name() + " cannot be empty");
        }
        checkChoices(field, values);
        return values;
    }

    /**
     * Checks that values are among those a field may take.
     *
     * @param field the field
     * @param values its new values
     * @throws InvalidPayloadException when one is not a choice the field offers
     * @throws RepositoryException when a value cannot be read as text
     */
    private static void checkChoices(final ContentFields.Field field, final List<Value> values)
        throws InvalidPayloadException, RepositoryException
    {
        final List<String> allowed = field.description().choices().stream().map(ContentFields.Choice::value).toList();
        for (final Value chosen : values) {
            if (!allowed.isEmpty() && !allowed.contains(chosen.getString())) {
                throw new InvalidPayloadException(field.name() + " cannot be " + chosen.getString());
            }
        }
    }

    /**
     * One value a patch gives a field.
     *
     * @param context the executing task's context
     * @param factory makes the value
     * @param field the field
     * @param value one value the patch gives
     * @return the value, empty for blank text
     * @throws InvalidPayloadException when the value is not one the field can hold
     * @throws RepositoryException when the value cannot be made
     */
    private static Optional<Value> single(final WorkflowTaskContext context, final ValueFactory factory,
        final ContentFields.Field field, final JsonValue value) throws InvalidPayloadException, RepositoryException
    {
        return switch (field.kind()) {
            case LONG -> Optional.of(factory.createValue(wholeNumber(field, value)));
            case DOUBLE -> Optional.of(factory.createValue(number(field, value).doubleValue()));
            case BOOLEAN -> Optional.of(factory.createValue(truth(field, value)));
            case REFERENCE -> reference(context, factory, field, value);
            case TEXT -> text(field, value).map(factory::createValue);
        };
    }

    /**
     * The whole number a patch gives.
     *
     * @param field the field
     * @param value the value
     * @return the number
     * @throws InvalidPayloadException when it is not a whole number, or too large to store
     */
    private static long wholeNumber(final ContentFields.Field field, final JsonValue value)
        throws InvalidPayloadException
    {
        if (!number(field, value).isIntegral()) {
            throw new InvalidPayloadException(field.name() + " must be a whole number");
        }
        try {
            return ((JsonNumber) value).longValueExact();
        } catch (final ArithmeticException e) {
            throw new InvalidPayloadException(field.name() + " is too large", e);
        }
    }

    /**
     * The number a patch gives.
     *
     * @param field the field
     * @param value the value
     * @return the number
     * @throws InvalidPayloadException when it is not a number
     */
    private static JsonNumber number(final ContentFields.Field field, final JsonValue value)
        throws InvalidPayloadException
    {
        if (!(value instanceof JsonNumber)) {
            throw new InvalidPayloadException(field.name() + " must be a number");
        }
        return (JsonNumber) value;
    }

    /**
     * The truth value a patch gives.
     *
     * @param field the field
     * @param value the value
     * @return the truth value
     * @throws InvalidPayloadException when it is neither true nor false
     */
    private static boolean truth(final ContentFields.Field field, final JsonValue value)
        throws InvalidPayloadException
    {
        if (value.getValueType() != JsonValue.ValueType.TRUE && value.getValueType() != JsonValue.ValueType.FALSE) {
            throw new InvalidPayloadException(field.name() + " must be true or false");
        }
        return value.getValueType() == JsonValue.ValueType.TRUE;
    }

    /**
     * The text a patch gives, trimmed.
     *
     * @param field the field
     * @param value the value
     * @return the text, empty when blank
     * @throws InvalidPayloadException when it is not a string
     */
    private static Optional<String> text(final ContentFields.Field field, final JsonValue value)
        throws InvalidPayloadException
    {
        if (!(value instanceof JsonString)) {
            throw new InvalidPayloadException(field.name() + " must be a string");
        }
        return Optional.of(((JsonString) value).getString().trim()).filter(text -> !text.isEmpty());
    }

    /**
     * The reference a patch gives, as the path of the node it points at.
     *
     * @param context the executing task's context
     * @param factory makes the value
     * @param field the reference field
     * @param value the value
     * @return the reference, empty when the path is blank
     * @throws InvalidPayloadException when it is not a path, or not of a node the field can point at
     * @throws RepositoryException when the reference cannot be made
     */
    private static Optional<Value> reference(final WorkflowTaskContext context, final ValueFactory factory,
        final ContentFields.Field field, final JsonValue value) throws InvalidPayloadException, RepositoryException
    {
        final Optional<String> path = text(field, value);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(factory.createValue(referenced(context, field, path.get()), field.weak()));
    }

    /**
     * The node a reference field is pointed at.
     *
     * @param context the executing task's context
     * @param field the reference field
     * @param path the path the patch gives
     * @return the node at that path
     * @throws InvalidPayloadException when there is none, or not of the type or not where the field accepts
     */
    private static Node referenced(final WorkflowTaskContext context, final ContentFields.Field field,
        final String path) throws InvalidPayloadException
    {
        final Resource referenced = context.getResourceResolver().getResource(path);
        final Node node = referenced == null ? null : referenced.adaptTo(Node.class);
        if (node == null || !accepts(field, referenced)) {
            throw new InvalidPayloadException("There is nothing " + field.name() + " can point to at " + path);
        }
        return node;
    }

    /**
     * Whether a reference field may point at a resource: one of the type it asks for, under the root it names.
     *
     * @param field the reference field
     * @param referenced the resource
     * @return whether it may
     */
    private static boolean accepts(final ContentFields.Field field, final Resource referenced)
    {
        final String type = field.description().referenceType();
        final String root = field.description().referenceRoot();
        return (type == null || referenced.isResourceType(type))
            && (root == null || referenced.getPath().startsWith(root.endsWith("/") ? root : root + "/"));
    }
}
