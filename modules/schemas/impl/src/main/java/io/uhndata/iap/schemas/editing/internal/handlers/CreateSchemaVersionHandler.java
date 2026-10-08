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

import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.utils.VersionNumbers;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Adds an empty version to the target schema, labelled with the event's {@code version}, and reports it as what
 * the execution created, so the steps after it act on the new version. It is numbered by the platform's rule for
 * versions, {@link VersionNumbers}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CreateSchemaVersionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "createSchemaVersion";

    /** The payload entry labelling the version. */
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
        if (SchemaContent.asSchema(target) == null) {
            throw SchemaContent.unsupportedTarget(HANDLER_NAME, target);
        }
        final int number = VersionNumbers.next(target, SchemaVersion.RESOURCE_TYPE);
        VersioningUtils.checkOut(target);
        final Resource version = context.getResourceResolver().create(target, VersionNumbers.nodeName(target, number),
            Map.of("jcr:primaryType", "sch:SchemaVersion", VERSION_PARAMETER, label(context, number)));
        context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, version.getPath());
    }

    /**
     * The version label the event asks for, or the next whole number.
     *
     * @param context the executing task's context
     * @param number the number the version is named with
     * @return a non-blank label
     * @throws InvalidPayloadException when a label is given but is not usable
     */
    private static String label(final WorkflowTaskContext context, final int number) throws InvalidPayloadException
    {
        if (context.getEvent().get(VERSION_PARAMETER) == null) {
            return VersionNumbers.defaultLabel(number);
        }
        return Payloads.requireText(context.getEvent(), VERSION_PARAMETER, "The version label cannot be blank");
    }
}
