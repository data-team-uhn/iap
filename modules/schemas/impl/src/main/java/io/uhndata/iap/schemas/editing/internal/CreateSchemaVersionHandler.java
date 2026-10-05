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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.utils.NodeNameUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Adds an empty version to the target schema, labelled with the event's {@code version}, and reports it as what
 * the execution created, so the steps after it act on the new version.
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

    /** A leading whole number, after an optional {@code v}: {@code v3}, {@code 3.0}, {@code 3}. */
    private static final Pattern NUMBERED = Pattern.compile("^[vV]?(\\d{1,9})");

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Schema schema = SchemaContent.asSchema(target);
        if (schema == null) {
            throw SchemaContent.unsupportedTarget(HANDLER_NAME, target);
        }
        final int number = nextNumber(schema);
        SchemaContent.checkOut(target);
        final Resource version = context.getResourceResolver().create(target,
            NodeNameUtils.findFreeName(target, "v" + number),
            Map.of("jcr:primaryType", "sch:SchemaVersion", VERSION_PARAMETER, label(context, number)));
        context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, version.getPath());
    }

    /**
     * The number of the next version: one past the largest a version of the schema is named with, so that a
     * version discarded from the middle leaves no number for a new one to take again. Labels are not read, since
     * they are free text, as likely a year as a number.
     *
     * @param schema the schema gaining a version
     * @return a number no version of the schema is named with yet
     */
    private static int nextNumber(final Schema schema)
    {
        return schema.getVersions().stream()
            .mapToInt(version -> numberIn(version.getName()))
            .max()
            .orElse(0) + 1;
    }

    /**
     * The whole number a version's name starts with: {@code v3} as this handler names them, or {@code 3.0} as
     * content imported by hand may be.
     *
     * @param name a version's node name
     * @return the number, or 0 when there is none
     */
    private static int numberIn(final String name)
    {
        final Matcher number = NUMBERED.matcher(name);
        return number.find() ? Integer.parseInt(number.group(1)) : 0;
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
        final Object label = context.getEvent().get(VERSION_PARAMETER);
        if (label == null) {
            return number + ".0";
        }
        if (!(label instanceof String) || ((String) label).isBlank()) {
            throw new InvalidPayloadException("The version label cannot be blank");
        }
        return ((String) label).trim();
    }
}
