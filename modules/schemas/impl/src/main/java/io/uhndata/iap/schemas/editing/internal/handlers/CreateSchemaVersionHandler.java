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
package io.uhndata.iap.schemas.editing.internal.handlers;

import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.utils.VersionNumbers;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Adds an empty version to the target schema, and reports it as what the execution created, so the steps after it
 * act on the new version. {@link VersionNumbers} names and numbers it. Its label is the event's {@code version}, or
 * else its number followed by {@code .0}, such as {@code 3.0}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CreateSchemaVersionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "createSchemaVersion";

    /** The payload entry holding the version's label. */
    static final String VERSION_PARAMETER = "version";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        if (!target.isResourceType(Schema.RESOURCE_TYPE)) {
            throw new WorkflowDefinitionException("The " + HANDLER_NAME + " handler does not serve "
                + target.getResourceType());
        }
        final int number = VersionNumbers.next(target, SchemaVersion.RESOURCE_TYPE);
        VersioningUtils.checkOut(target);
        final Resource version = context.getResourceResolver().create(target, VersionNumbers.nodeName(target, number),
            Map.of("jcr:primaryType", "sch:SchemaVersion", VERSION_PARAMETER, label(context, number)));
        context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, version.getPath());
    }

    /**
     * The label the event asks for, trimmed, or the default label for the version's number.
     *
     * @param context the executing task's context
     * @param number the number the version is named with, such as 3 for {@code v3}
     * @return a label that is not blank, such as {@code 3.0}
     * @throws InvalidPayloadException if the event gives a label that is blank or is not a single text value
     */
    private static String label(final WorkflowTaskContext context, final int number) throws InvalidPayloadException
    {
        if (context.getEvent().get(VERSION_PARAMETER) == null) {
            return VersionNumbers.defaultLabel(number);
        }
        return Payloads.requireText(context.getEvent(), VERSION_PARAMETER, "The version label cannot be blank");
    }
}
