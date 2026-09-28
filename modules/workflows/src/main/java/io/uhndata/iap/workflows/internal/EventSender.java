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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The {@code sendEvent} service task: sends the event its activity names in {@code message} on to the
 * {@link ExecutionHost host}, with the triggering event's payload, e.g. a schema just created is sent
 * {@code createVersion}. The system workflow waiting for it runs as part of the same execution, and is refused
 * exactly as it would be if the user had sent the event themselves.
 *
 * <p>Built into the engine rather than registered, like {@code startWorkflow}: running another workflow is the
 * engine's own business.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EventSender
{
    /** The name an activity uses to ask for this. */
    static final String HANDLER_NAME = "sendEvent";

    /** The activity property naming the event to send. */
    private static final String MESSAGE_PARAMETER = "message";

    private EventSender()
    {
    }

    /**
     * Sends the configured event on.
     *
     * @param context the executing task's context
     * @param dispatch how the engine runs the workflow waiting for the event
     * @throws WorkflowException when the activity is misconfigured, or the event is refused or fails
     * @throws PersistenceException when the chained workflow's writes fail
     */
    static void execute(final WorkflowTaskContext context, final Dispatch dispatch)
        throws WorkflowException, PersistenceException
    {
        final Object message = context.getActivity().get(MESSAGE_PARAMETER);
        if (!(message instanceof String) || ((String) message).isBlank()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " does not configure which " + MESSAGE_PARAMETER + " to send");
        }
        dispatch.send(ExecutionHost.of(context),
            new WorkflowEvent((String) message, context.getEvent().getPayload()));
    }

    /**
     * How the engine runs the system workflow waiting for a sent event.
     *
     * @version $Id$
     * @since 0.1.0
     */
    interface Dispatch
    {
        /**
         * Runs the workflow waiting for an event, without committing.
         *
         * @param target the resource the event is sent to, backed by the engine's own session
         * @param event the event
         * @throws WorkflowException when the event is refused or the workflow fails
         * @throws PersistenceException when the workflow's writes fail
         */
        void send(Resource target, WorkflowEvent event) throws WorkflowException, PersistenceException;
    }
}
