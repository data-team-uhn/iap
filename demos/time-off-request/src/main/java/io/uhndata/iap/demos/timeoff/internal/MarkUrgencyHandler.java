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
package io.uhndata.iap.demos.timeoff.internal;

import java.time.LocalDate;

import org.apache.sling.api.resource.PersistenceException;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Flags a request that is about to start, at the moment it is sent.
 *
 * <p>The nightly sweep would find it too, but not until tonight — and a request sent this morning for tomorrow is
 * exactly the one somebody needs to see today. So the process says it: a service task on the arc leaving the
 * task that sends the request, which is the first moment the answers are final.</p>
 *
 * <p>Both callers of {@link TimeOffUrgency} do the same thing to the same request, deliberately: the sweep is not
 * a correction of this and this is not an optimisation of the sweep. One of them runs when the request changes and
 * the other when the calendar does, and a request only becomes urgent for one of those two reasons.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class MarkUrgencyHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "markTimeOffUrgency";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws PersistenceException
    {
        TimeOffUrgency.mark(context.getTarget(), LocalDate.now());
    }
}
