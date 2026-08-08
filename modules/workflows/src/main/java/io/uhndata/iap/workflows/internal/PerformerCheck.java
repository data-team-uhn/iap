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

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.ResourceResolver;

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
 * @version $Id$
 * @since 0.1.0
 */
final class PerformerCheck
{
    /** The built-in group that stands for every authenticated user. */
    private static final String EVERYONE = "everyone";

    /** What an actor is told when the definition does not admit them; deliberately the same for every reason. */
    private static final String REFUSAL = "You are not allowed to do this";

    private PerformerCheck()
    {
    }

    /**
     * Refuses the actor unless the node names them, directly or through a group they belong to. An unnamed actor
     * facing a node that names nobody is refused too: a definition has to say who may use it.
     *
     * @param serviceResolver the engine's own session, used to look the actor up
     * @param node the flow node execution wants to pass through
     * @param actor the user who fired the event, as their repository user id
     * @throws NotAuthorizedException when the node does not admit this actor
     * @throws WorkflowFailedException when the repository cannot say who the actor is
     */
    static void verify(final ResourceResolver serviceResolver, final FlowNode node, final String actor)
        throws WorkflowException
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
        final List<String> performers = node.getPerformers();
        // "everyone" is matched by name rather than by membership: it is a dynamic principal an authorizable does
        // not necessarily report belonging to, and every authenticated actor is in it by definition
        if (!performers.contains(EVERYONE) && !isNamed(authorizable, performers)) {
            throw new NotAuthorizedException(REFUSAL);
        }
    }

    /**
     * Whether any of the named principals is the actor themselves or a group they belong to. An empty list matches
     * nothing, which is how "a definition that names no performers admits nobody" is enforced.
     *
     * @param authorizable the actor
     * @param performers the principals the node admits
     * @return {@code true} if the actor is among them
     * @throws WorkflowFailedException when the actor's group membership cannot be read
     */
    private static boolean isNamed(final Authorizable authorizable, final List<String> performers)
        throws WorkflowFailedException
    {
        try {
            final Set<String> identities = new HashSet<>();
            identities.add(authorizable.getID());
            // Transitive, so that naming a group also admits the members of its member groups
            for (final Iterator<Group> groups = authorizable.memberOf(); groups.hasNext();) {
                identities.add(groups.next().getID());
            }
            return performers.stream().anyMatch(identities::contains);
        } catch (final RepositoryException e) {
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
