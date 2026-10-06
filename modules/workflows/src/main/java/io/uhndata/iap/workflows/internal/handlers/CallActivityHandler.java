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

import org.apache.sling.api.resource.PersistenceException;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * What a call activity does: hands the work on to another workflow and waits for it to finish. It sends the event
 * its activity names in {@code message} on to the {@link ExecutionHost host}, with the triggering event's payload,
 * e.g. a schema just created is sent {@code createVersion}. The system workflow waiting for it runs as part of the
 * same execution, and is refused exactly as it would be if the user had sent the event themselves.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CallActivityHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler, and the one the vocabulary sets on every call activity. */
    public static final String HANDLER_NAME = "callActivity";

    /** The activity property naming the event to send. */
    private static final String MESSAGE_PARAMETER = "message";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Object message = context.getActivity().get(MESSAGE_PARAMETER);
        if (!(message instanceof String) || ((String) message).isBlank()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " does not configure which " + MESSAGE_PARAMETER + " to send");
        }
        context.sendEvent(ExecutionHost.of(context),
            new WorkflowEvent((String) message, context.getEvent().getPayload()));
    }
}
