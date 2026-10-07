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
package io.uhndata.iap.workflows.internal.handlers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.spi.AbstractPropertiesHandler;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Writes plain text properties from the payload onto the resource the step acts on — renaming a workflow, and
 * whatever else a deployment decides is editable that way.
 *
 * <p>Which properties those are is the activity's business: {@code editable} lists the ones a caller may set, and
 * {@code required} the ones that may not be cleared. That listing is the whole of the safety here — without it the
 * handler would be an open write to whatever the caller cared to name, {@code jcr:primaryType} included. A payload
 * entry not named in {@code editable} is ignored rather than refused, since a form carries entries that are not
 * properties at all.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class SavePropertiesHandler extends AbstractPropertiesHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "saveProperties";

    /** The activity property listing which payload entries may be written. */
    private static final String EDITABLE_PARAMETER = "editable";

    /** The activity property listing which of them may not be cleared. */
    private static final String REQUIRED_PARAMETER = "required";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    @NotNull
    protected List<String> allowed(@NotNull final WorkflowTaskContext context, @NotNull final Resource target)
    {
        return names(context, EDITABLE_PARAMETER);
    }

    @Override
    @NotNull
    protected EditableProperty property(@NotNull final WorkflowTaskContext context, @NotNull final Resource target,
        @NotNull final String name)
    {
        return new TextProperty(name, names(context, REQUIRED_PARAMETER).contains(name));
    }

    @Override
    @NotNull
    protected Map<String, Object> requested(@NotNull final WorkflowTaskContext context)
    {
        return context.getEvent().getPayload();
    }

    @Override
    protected boolean refusesUnlisted()
    {
        return false;
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

    /**
     * A property holding text.
     *
     * @param name the property's name
     * @param mandatory whether it may not be cleared
     * @version $Id$
     * @since 0.1.0
     */
    private record TextProperty(@NotNull String name, boolean mandatory) implements EditableProperty
    {
        @Override
        @Nullable
        public String referenceType()
        {
            return null;
        }
    }
}
