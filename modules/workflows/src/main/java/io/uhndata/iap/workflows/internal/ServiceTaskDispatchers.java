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

import java.util.List;
import java.util.Objects;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import io.uhndata.iap.workflows.spi.ServiceTaskHandler;

/**
 * Hands out the {@link ServiceTaskDispatcher} for one delivery. The services every delivery shares are injected here
 * once.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskDispatchers.class)
public class ServiceTaskDispatchers
{
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    private volatile List<ServiceTaskHandler> handlers;

    private final FlowRouting routing;

    private final InstanceRunners runners;

    /**
     * Constructor.
     *
     * @param routing moves a system workflow's run from node to node
     * @param runners hands out the runner for any instance a delivery starts
     */
    @Activate
    public ServiceTaskDispatchers(@Reference final FlowRouting routing, @Reference final InstanceRunners runners)
    {
        this.routing = routing;
        this.runners = runners;
    }

    /**
     * Replaces the service task handlers, as the service registry does when they come and go.
     *
     * @param handlers every handler
     */
    void setHandlers(final List<ServiceTaskHandler> handlers)
    {
        this.handlers = handlers;
    }

    /**
     * A dispatcher for one delivery, working from the handlers registered now.
     *
     * @param chain how the engine admits an event a service task sends
     * @return the dispatcher
     */
    ServiceTaskDispatcher create(final ServiceTaskDispatcher.EventChain chain)
    {
        // Read once: the registry replaces the list, and a REPLACE field is null until the first handler arrives
        return new ServiceTaskDispatcher(Objects.requireNonNullElse(this.handlers, List.of()), chain, this.routing,
            this.runners);
    }
}
