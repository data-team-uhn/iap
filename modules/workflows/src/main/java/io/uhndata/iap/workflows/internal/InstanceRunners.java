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

import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Hands out the {@link InstanceRunner} that carries user workflows on for one run: the engine's session, the user the
 * run acts for, and how it performs service tasks. The services every run shares are injected here once.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = InstanceRunners.class)
public class InstanceRunners
{
    private final FlowRouting routing;

    private final Performers performers;

    private final HostAccess hostAccess;

    /**
     * Constructor.
     *
     * @param routing what decides where tokens go
     * @param performers says who the names in a definition stand for
     * @param hostAccess grants read access on the resources workflows drive
     */
    @Activate
    public InstanceRunners(@Reference final FlowRouting routing, @Reference final Performers performers,
        @Reference final HostAccess hostAccess)
    {
        this.routing = routing;
        this.performers = performers;
        this.hostAccess = hostAccess;
    }

    /**
     * A runner for one run.
     *
     * @param resolver the engine's own session, which everything is read and written through
     * @param performer how a service task met along the way gets performed
     * @param actor the user whose action is moving the instance
     * @return the runner
     */
    InstanceRunner runnerFor(final ResourceResolver resolver, final InstanceRunner.ServiceTaskPerformer performer,
        final String actor)
    {
        return new InstanceRunner(new InstanceRecords(resolver, this.performers), performer, actor, this.routing,
            this.hostAccess);
    }
}
