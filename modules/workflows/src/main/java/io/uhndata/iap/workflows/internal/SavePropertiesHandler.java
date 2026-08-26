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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Writes plain text properties from the payload onto the thing the event was aimed at — renaming a workflow, and
 * whatever else a deployment decides is editable that way.
 *
 * <p>Which properties those are is the activity's business, not this handler's: {@code editable} lists the ones a
 * caller may set, and {@code required} the subset that has to arrive with something in it. A payload entry not
 * named in {@code editable} is ignored rather than refused, so a client sending a field this deployment does not
 * accept is simply not granted it. That listing is the whole of the safety here — without it the handler would be
 * an open write to whatever the caller cared to name, {@code jcr:primaryType} included, which is exactly the
 * direct-CRUD door the workflows are replacing.</p>
 *
 * <p>An editable property that arrives blank is removed rather than stored empty: a title cleared in a form is a
 * property the entity no longer carries, not one it carries the empty string in. A property named as required is
 * the exception, refused instead.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class SavePropertiesHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "saveProperties";

    /** The activity property listing which payload entries may be written. */
    private static final String EDITABLE = "editable";

    /** The activity property listing which of them must arrive with a value. */
    private static final String REQUIRED = "required";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final List<String> editable = names(context, EDITABLE);
        if (editable.isEmpty()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " does not list which properties it is editable to write");
        }
        final List<String> required = names(context, REQUIRED);
        final ModifiableValueMap properties = Objects.requireNonNull(
            context.getTarget().adaptTo(ModifiableValueMap.class),
            "The engine can always write what it can read");
        for (final String name : editable) {
            final String value = Payloads.text(context.getEvent(), name);
            if (value != null) {
                properties.put(name, value);
            } else if (required.contains(name)) {
                throw new InvalidPayloadException("A " + name + " is required");
            } else {
                properties.remove(name);
            }
        }
    }

    /**
     * One of the activity's list-valued configuration properties, tolerating the single-valued form a JCR property
     * collapses to when it was authored with one entry.
     *
     * @param context the handler's context
     * @param name the configuration property to read
     * @return the names it lists, empty if it lists none
     */
    private static List<String> names(final WorkflowTaskContext context, final String name)
    {
        final Object configured = context.getActivity().get(name);
        if (configured instanceof String[]) {
            return Arrays.asList((String[]) configured);
        }
        return configured instanceof String ? List.of((String) configured) : List.of();
    }
}
