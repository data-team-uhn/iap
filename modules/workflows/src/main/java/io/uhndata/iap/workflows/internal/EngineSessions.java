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

import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.workflows.api.WorkflowFailedException;

/**
 * Opens the workflow engine's own session, in which every delivery runs privileged.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = EngineSessions.class)
public class EngineSessions
{
    /** The subservice name under which the engine's service user is mapped. */
    private static final String SUBSERVICE_NAME = "workflows";

    private final ResourceResolverFactory resolverFactory;

    /**
     * Constructor.
     *
     * @param resolverFactory where the sessions come from
     */
    @Activate
    public EngineSessions(@Reference final ResourceResolverFactory resolverFactory)
    {
        this.resolverFactory = resolverFactory;
    }

    /**
     * Opens a session as the engine's service user.
     *
     * @return a service resource resolver, to be closed by the caller
     * @throws WorkflowFailedException when the service user is not available
     */
    ResourceResolver open() throws WorkflowFailedException
    {
        try {
            return this.resolverFactory
                .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME));
        } catch (final LoginException e) {
            throw new WorkflowFailedException("The workflow engine's service user is not available", e);
        }
    }
}
