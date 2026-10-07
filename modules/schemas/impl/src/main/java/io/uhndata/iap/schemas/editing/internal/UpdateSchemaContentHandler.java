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
import java.util.List;
import java.util.Map;

import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.AbstractPropertiesHandler;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Applies a {@link SchemaPatch} to a schema or a schema version, limited to the fields the activity lists in its
 * {@code fields} configuration: which fields may change in which state is decided by the workflows, one per
 * state, rather than here.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class UpdateSchemaContentHandler extends AbstractPropertiesHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "updateSchemaContent";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    @NotNull
    protected List<String> allowed(@NotNull final WorkflowTaskContext context) throws WorkflowException
    {
        typeOf(context.getTarget());
        return SchemaFields.allowedBy(context.getActivity());
    }

    @Override
    @NotNull
    protected EditableProperty property(@NotNull final WorkflowTaskContext context, @NotNull final String name)
        throws WorkflowException
    {
        final String type = typeOf(context.getTarget());
        return SchemaFields.find(type, name).orElseThrow(() -> new WorkflowDefinitionException("The activity "
            + context.getActivity().getPath() + " allows " + name + ", which is not a field of " + type));
    }

    @Override
    @NotNull
    protected Map<String, Object> requested(@NotNull final WorkflowTaskContext context) throws WorkflowException
    {
        final Map<String, Object> requested = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonValue> entry : SchemaPatch.read(context).entrySet()) {
            final JsonValue value = entry.getValue();
            requested.put(entry.getKey(), value instanceof JsonString ? ((JsonString) value).getString()
                : value.getValueType() == JsonValue.ValueType.NULL ? null : value);
        }
        return requested;
    }

    /**
     * Which kind of schema content the target is.
     *
     * @param target the workflow's target
     * @return the resource type its fields are listed under
     * @throws WorkflowDefinitionException when it is neither a schema nor a schema version
     */
    private static String typeOf(final Resource target) throws WorkflowDefinitionException
    {
        if (SchemaContent.asVersion(target) != null) {
            return SchemaVersion.RESOURCE_TYPE;
        }
        if (SchemaContent.asSchema(target) != null) {
            return Schema.RESOURCE_TYPE;
        }
        throw SchemaContent.unsupportedTarget(HANDLER_NAME, target);
    }
}
