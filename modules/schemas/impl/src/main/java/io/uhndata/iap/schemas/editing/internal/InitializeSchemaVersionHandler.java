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

import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.LifecycleState;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.utils.NodeNameUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Gives a schema just created by an earlier step its first version: an empty draft, labelled with the event's
 * {@code version}, {@code 1.0} by default. The created path stays the schema's.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class InitializeSchemaVersionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "initializeSchemaVersion";

    /** The payload entry labelling the version. */
    static final String VERSION_PARAMETER = "version";

    private static final String DEFAULT_LABEL = "1.0";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Object created = context.getVariable(WorkflowResult.CREATED_PATH_VARIABLE);
        final Resource schema = created instanceof String
            ? context.getResourceResolver().getResource((String) created) : null;
        if (schema == null || !schema.isResourceType(Schema.RESOURCE_TYPE)) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " must follow a step that creates the schema");
        }
        final Resource version = context.getResourceResolver().create(schema,
            NodeNameUtils.findFreeName(schema, "v1"),
            Map.of("jcr:primaryType", "sch:SchemaVersion", VERSION_PARAMETER, label(context)));
        SchemaContent.setLifecycle(version, LifecycleState.DRAFT);
    }

    /**
     * The version label the event asks for, or the default.
     *
     * @param context the executing task's context
     * @return a non-blank label
     * @throws InvalidPayloadException when a label is given but is not usable
     */
    static String label(final WorkflowTaskContext context) throws InvalidPayloadException
    {
        final Object label = context.getEvent().get(VERSION_PARAMETER);
        if (label == null) {
            return DEFAULT_LABEL;
        }
        if (!(label instanceof String) || ((String) label).isBlank()) {
            throw new InvalidPayloadException("The version label cannot be blank");
        }
        return ((String) label).trim();
    }
}
