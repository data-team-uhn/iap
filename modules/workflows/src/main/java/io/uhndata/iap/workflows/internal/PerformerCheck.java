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

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.principals.api.PrincipalLookupException;
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.FlowNode;

/**
 * Decides whether an actor may make execution pass through a flow node, by asking the node itself.
 *
 * <p>This is the whole authorization story of the platform. The content workflows manage grants no rights to
 * anyone, and the engine performs every write as its own service user, so nothing downstream will refuse an actor
 * who should not be here — by the time a handler runs, the repository is being written to with full privileges.
 * The refusal has to happen here, before the first step, and it has to be strict: an actor passes only if the
 * definition named them, or named a group they belong to.</p>
 *
 * <p>What a definition's names mean — {@code @creator} for whoever raised the resource being worked on,
 * {@code everyone} for any authenticated user, a group however a deployment stores it — is the
 * {@link PrincipalService}'s answer, so a task saying "yours" in a listing and this check refusing its completion
 * cannot disagree about what a name means. The one judgement kept here is the administrator bypass: administrators
 * pass everything, exactly as they bypass access control in the repository itself, since without it a deployment
 * could write a definition that locks its own authors out with no way back in.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class PerformerCheck
{
    /** What an actor is told when the definition does not admit them; deliberately the same for every reason. */
    private static final String REFUSAL = "You are not allowed to do this";

    private PerformerCheck()
    {
    }

    /**
     * Refuses the actor unless the node names them, directly or through a group they belong to. An unnamed actor
     * facing a node that names nobody is refused too: a definition has to say who may use it.
     *
     * @param principals the vocabulary the node's names are read in
     * @param serviceResolver the engine's own session, used to look the actor up
     * @param host the resource being worked on, which is what {@code @creator} is asked about
     * @param node the flow node execution wants to pass through
     * @param actor the user who fired the event, as their repository user id
     * @throws NotAuthorizedException when the node does not admit this actor
     * @throws WorkflowFailedException when the repository cannot say who the actor is
     */
    static void verify(final PrincipalService principals, final ResourceResolver serviceResolver,
        final Resource host, final FlowNode node, final String actor) throws WorkflowException
    {
        final Authorizable authorizable = lookUp(serviceResolver, actor);
        if (authorizable == null) {
            throw new NotAuthorizedException(REFUSAL);
        }
        // Administrators pass everything, exactly as they bypass access control in the repository itself. Without
        // this, a deployment could write a definition that locks its own authors out with no way back in.
        if (authorizable instanceof User && ((User) authorizable).isAdmin()) {
            return;
        }
        try {
            if (!principals.isOneOf(actor, principals.resolve(node.getPerformers(), host),
                serviceResolver)) {
                throw new NotAuthorizedException(REFUSAL);
            }
        } catch (final PrincipalLookupException e) {
            throw new WorkflowFailedException("Could not determine what groups the requesting user belongs to", e);
        }
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
