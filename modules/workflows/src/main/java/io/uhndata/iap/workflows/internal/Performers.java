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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.principals.api.PrincipalContext;
import io.uhndata.iap.principals.api.PrincipalLookupException;
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.FlowNode;

/**
 * Says who the names in a workflow definition stand for, through the {@link PrincipalService}. Every part of the
 * engine asks here, so a name means the same people everywhere.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Performers.class)
public class Performers
{
    private final PrincipalService principals;

    /**
     * Constructor.
     *
     * @param principals resolves the people a definition names
     */
    @Activate
    public Performers(@Reference final PrincipalService principals)
    {
        this.principals = principals;
    }

    /**
     * The principals a node names, with special names such as {@code @creator} answered about a resource.
     *
     * @param node the flow node
     * @param subject the resource being worked on
     * @return the principal names
     */
    List<String> resolve(final FlowNode node, final Resource subject)
    {
        return resolve(node.getPerformers(), subject);
    }

    /**
     * The principals a list names, with special names such as {@code @creator} answered about a resource.
     *
     * @param names the names as a definition writes them
     * @param subject the resource being worked on
     * @return the principal names
     */
    List<String> resolve(final List<String> names, final Resource subject)
    {
        return this.principals.resolve(names, PrincipalContext.about(subject));
    }

    /**
     * Whether a user is one of the given principals, directly or through a group, however a deployment stores it.
     *
     * @param actor the user id
     * @param resolved principal names, special names already answered
     * @param serviceResolver the engine's own session, which membership is asked through
     * @return {@code true} if the user is one of them
     * @throws WorkflowFailedException when the user's groups cannot be determined
     */
    boolean isOneOf(final String actor, final List<String> resolved, final ResourceResolver serviceResolver)
        throws WorkflowFailedException
    {
        try {
            return this.principals.isOneOf(actor, resolved, serviceResolver);
        } catch (final PrincipalLookupException e) {
            throw new WorkflowFailedException("Could not determine what groups the requesting user belongs to", e);
        }
    }

    /**
     * Looks a user up once, to be checked against any number of nodes.
     *
     * @param serviceResolver the engine's own session, used to look the user up
     * @param actor the user who fired the event, as their repository user id
     * @return a check for this user
     * @throws WorkflowFailedException when the repository cannot say who the user is
     */
    PerformerCheck createCheck(final ResourceResolver serviceResolver, final String actor)
        throws WorkflowFailedException
    {
        return new PerformerCheck(this, serviceResolver, actor);
    }
}
