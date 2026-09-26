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
package io.uhndata.iap.schemas.editing.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Applies a {@link SchemaPatch} to a schema or a schema version. On a version that is no longer a draft only
 * the wording may change; anything else is refused, since submissions may already depend on it.
 *
 * <p>The whole patch is checked before anything is written, so a refusal leaves the content as it was.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class UpdateSchemaContentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "updateSchemaContent";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final SchemaVersion version = SchemaContent.asVersion(target);
        if (version == null && SchemaContent.asSchema(target) == null) {
            throw SchemaContent.unsupportedTarget(HANDLER_NAME, target);
        }
        final String type = version != null ? SchemaVersion.RESOURCE_TYPE : Schema.RESOURCE_TYPE;
        final Map<SchemaFields.Field, Object> changes = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonValue> entry : SchemaPatch.read(context).entrySet()) {
            final SchemaFields.Field field = SchemaFields.find(type, entry.getKey())
                .orElseThrow(() -> new InvalidPayloadException(entry.getKey() + " cannot be edited here"));
            if (version != null && !version.isDraft() && !field.wording()) {
                throw new NoApplicableWorkflowException("Version " + version.getVersion()
                    + " is published, so only its wording can change, and " + field.name()
                    + " is not wording; create a new version instead");
            }
            changes.put(field, value(context, field, entry.getValue()));
        }
        for (final Map.Entry<SchemaFields.Field, Object> change : changes.entrySet()) {
            write(target, change.getKey(), change.getValue());
        }
    }

    /**
     * Validates one requested value.
     *
     * @param context the executing task's context
     * @param field the field being set
     * @param value the requested value
     * @return the string or the referenced resource to store, or {@code null} to remove the property
     * @throws InvalidPayloadException when the value does not suit the field
     */
    private Object value(final WorkflowTaskContext context, final SchemaFields.Field field, final JsonValue value)
        throws InvalidPayloadException
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
        if (field.kind() == SchemaFields.Kind.TEXT) {
            return text;
        }
        final Resource referenced = context.getResourceResolver().getResource(text);
        if (referenced == null || !referenced.isResourceType(field.referenceType())) {
            throw new InvalidPayloadException("There is nothing " + field.name() + " can point to at " + text);
        }
        return referenced;
    }

    /**
     * Writes one validated value.
     *
     * @param target the content being edited
     * @param field the field being set
     * @param value what {@link #value} made of the request
     * @throws PersistenceException when the content cannot be modified
     */
    private void write(final Resource target, final SchemaFields.Field field, final Object value)
        throws PersistenceException
    {
        final ModifiableValueMap values = target.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            throw new PersistenceException("The resource " + target.getPath() + " cannot be modified");
        }
        if (value == null) {
            values.remove(field.name());
        } else if (value instanceof Resource) {
            // The Sling API cannot write a REFERENCE property, only a string holding an identifier
            final Node node = target.adaptTo(Node.class);
            final Node referenced = ((Resource) value).adaptTo(Node.class);
            if (node == null || referenced == null) {
                throw new PersistenceException("Cannot set " + field.name() + " on " + target.getPath());
            }
            try {
                node.setProperty(field.name(), referenced);
            } catch (final RepositoryException e) {
                throw new PersistenceException("Cannot set " + field.name() + " on " + target.getPath(), e);
            }
        } else {
            values.put(field.name(), value);
        }
    }
}
