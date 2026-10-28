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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.FlowNode;

/**
 * Decides whether an actor may make execution pass through a flow node, by asking the node itself.
 *
 * <p>Nothing downstream refuses an actor who should not be here. The workflows this content manages grant no
 * rights to anyone, and the engine performs every write as its own service user. By the time a handler runs, the
 * repository is being written with full privileges. The refusal happens here, before the first step. An actor
 * passes only if the definition named them, or named a group they belong to.</p>
 *
 * <p>{@link Performers} says who a definition's names stand for. {@code @creator} is whoever raised the
 * resource being worked on, {@code everyone} is any authenticated user, and a group counts however a deployment
 * stores it. This class adds the administrator bypass, and refuses an actor the repository does not know.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class PerformerCheck
{
    /** What an actor is told when the definition does not admit them. The same words whatever the reason. */
    private static final String REFUSAL_MESSAGE = "You are not allowed to do this";

    /** Says who the names in a definition stand for. */
    private final Performers performers;

    /** The engine's own session, which membership is asked through. */
    private final ResourceResolver serviceResolver;

    /** The actor's user id. */
    private final String actor;

    /** The actor, or {@code null} when the repository does not know them. */
    private final Authorizable authorizable;

    /** Whether the actor is among a list of principals, by the resolved list, asked once per list. */
    private final Map<List<String>, Boolean> memberships = new HashMap<>();

    /**
     * Looks the actor up once, to be asked about any number of nodes.
     *
     * @param performers says who the names in a definition stand for
     * @param serviceResolver the engine's own session, used to look the actor up
     * @param actor the user who fired the event, as their repository user id
     * @throws WorkflowFailedException when the repository cannot say who the actor is
     */
    PerformerCheck(final Performers performers, final ResourceResolver serviceResolver, final String actor)
        throws WorkflowFailedException
    {
        this.performers = performers;
        this.serviceResolver = serviceResolver;
        this.actor = actor;
        this.authorizable = lookUp(serviceResolver, actor);
    }

    /**
     * Refuses the actor unless the node names them, directly or through a group they belong to. Both halves fail
     * closed: an actor the repository does not know is refused, and a node that names nobody admits nobody.
     *
     * @param node the flow node execution wants to pass through
     * @param subject the resource being worked on, whose creator {@code @creator} names
     * @throws NotAuthorizedException when the node does not admit this actor
     * @throws WorkflowFailedException when the actor's group membership cannot be read
     */
    void verify(final FlowNode node, final Resource subject) throws WorkflowException
    {
        verify(node.getPerformers(), subject);
    }

    /**
     * Refuses the actor unless the list admits them. A caller passes the list itself when a node's performers default
     * to someone if it names nobody.
     *
     * @param admitted the principals admitted, as a definition writes them
     * @param subject the resource being worked on, whose creator {@code @creator} names
     * @throws NotAuthorizedException when the list does not admit this actor
     * @throws WorkflowFailedException when the actor's group membership cannot be read
     */
    void verify(final List<String> admitted, final Resource subject) throws WorkflowException
    {
        if (!admits(admitted, subject)) {
            throw new NotAuthorizedException(REFUSAL_MESSAGE);
        }
    }

    /**
     * Whether the node names the actor, directly or through a group they belong to.
     *
     * @param node the flow node execution wants to pass through
     * @param subject the resource being worked on, whose creator {@code @creator} names
     * @return {@code true} if the actor may make execution pass through it
     * @throws WorkflowFailedException when the actor's group membership cannot be read
     */
    boolean admits(final FlowNode node, final Resource subject) throws WorkflowFailedException
    {
        return admits(node.getPerformers(), subject);
    }

    /**
     * Whether the list admits the actor.
     *
     * @param admitted the principals admitted, as a definition writes them
     * @param subject the resource being worked on, whose creator {@code @creator} names
     * @return {@code true} if the actor is among them
     * @throws WorkflowFailedException when the actor's group membership cannot be read
     */
    private boolean admits(final List<String> admitted, final Resource subject) throws WorkflowFailedException
    {
        if (this.authorizable == null) {
            return false;
        }
        // Administrators pass everything, as they bypass access control in the repository itself. Without it, a
        // definition can lock its own authors out with no way back in.
        if (this.authorizable instanceof User && ((User) this.authorizable).isAdmin()) {
            return true;
        }
        final List<String> resolved = this.performers.resolve(admitted, subject);
        Boolean member = this.memberships.get(resolved);
        if (member == null) {
            member = this.performers.isOneOf(this.actor, resolved, this.serviceResolver);
            this.memberships.put(resolved, member);
        }
        return member;
    }

    /**
     * Finds the actor in the repository's user store.
     *
     * @param serviceResolver the engine's own session
     * @param actor the user id to look up, {@code null} for an unauthenticated caller
     * @return the actor, or {@code null} if there is nobody by that name
     * @throws WorkflowFailedException when the user store cannot be reached
     */
    private static Authorizable lookUp(final ResourceResolver serviceResolver, final String actor)
        throws WorkflowFailedException
    {
        if (actor == null) {
            return null;
        }
        final Session session = serviceResolver.adaptTo(Session.class);
        if (!(session instanceof JackrabbitSession)) {
            throw new WorkflowFailedException("The repository cannot be asked who its users are");
        }
        try {
            final UserManager userManager = ((JackrabbitSession) session).getUserManager();
            return userManager.getAuthorizable(actor);
        } catch (final RepositoryException e) {
            throw new WorkflowFailedException("Could not look up the user " + actor, e);
        }
    }
}
