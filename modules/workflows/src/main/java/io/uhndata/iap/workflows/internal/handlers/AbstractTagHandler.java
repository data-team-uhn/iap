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

import java.util.Objects;

import org.apache.sling.api.resource.PersistenceException;

import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * What the built-in tag service tasks share: the tag their activity names in its {@code tag} property, and the
 * {@link ExecutionHost host} they change. Tags are changed with system tags allowed.
 *
 * @version $Id$
 * @since 0.1.0
 */
abstract class AbstractTagHandler implements ServiceTaskHandler
{
    /** The activity property naming the tag. */
    private static final String TAG_PARAMETER = "tag";

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Object tag = context.getActivity().get(TAG_PARAMETER);
        if (!(tag instanceof String) || ((String) tag).isBlank()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " does not configure which " + TAG_PARAMETER + " to change");
        }
        final Taggable host = Objects.requireNonNull(ExecutionHost.of(context).adaptTo(Taggable.class),
            "Any resource adapts to Taggable");
        try {
            change(context, host, (String) tag);
        } catch (final IllegalArgumentException e) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " cannot change the tag " + tag + " on " + host.getPath() + ": " + e.getMessage());
        }
    }

    /**
     * Makes the change this service task is for.
     *
     * @param context the executing task's context
     * @param host the resource whose tags change
     * @param tag the tag the activity names
     * @throws PersistenceException when the host cannot be written
     */
    protected abstract void change(WorkflowTaskContext context, Taggable host, String tag)
        throws PersistenceException;
}
