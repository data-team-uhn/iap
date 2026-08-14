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
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;
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
 * <p>Two of the names a definition can use are not principals at all. {@code everyone} is the built-in group
 * meaning any authenticated user, and {@code @creator} means whoever the engine recorded as having raised the
 * resource being worked on — the one rule that a group can never express, and the one most processes need: a
 * request comes back to the person who made it, not to everyone who could have made one.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class PerformerCheck
{
    /** The name standing for whoever raised the resource being worked on, rather than for a principal. */
    static final String CREATOR = "@creator";

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
     * @param host the resource being worked on, which is what {@code @creator} is asked about
     * @param node the flow node execution wants to pass through
     * @param actor the user who fired the event, as their repository user id
     * @throws NotAuthorizedException when the node does not admit this actor
     * @throws WorkflowFailedException when the repository cannot say who the actor is
     */
    static void verify(final ResourceResolver serviceResolver, final Resource host, final FlowNode node,
        final String actor) throws WorkflowException
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
        if (!performers.contains(EVERYONE) && !raisedIt(host, performers, actor)
            && !isNamed(authorizable, performers)) {
            throw new NotAuthorizedException(REFUSAL);
        }
    }

    /**
     * Whether the node admits whoever raised the host, and this actor is them.
     *
     * <p>Asked of the host rather than of the repository's {@code jcr:createdBy}, which names the engine's own
     * service user for everything it writes; the engine records the human separately, and that is what this
     * compares against. A host nothing raised — a homepage, say — is nobody's, so this admits nobody.</p>
     *
     * @param host the resource being worked on
     * @param performers the principals the node admits
     * @param actor the user who fired the event
     * @return {@code true} if the node names {@code @creator} and this actor raised the host
     */
    private static boolean raisedIt(final Resource host, final List<String> performers, final String actor)
    {
        return performers.contains(CREATOR) && actor.equals(creatorOf(host));
    }

    /**
     * Who the engine recorded as having raised a resource.
     *
     * @param host the resource being worked on
     * @return their user id, or {@code null} if nothing raised it — a homepage, say, which is nobody's
     */
    @Nullable
    static String creatorOf(final Resource host)
    {
        final Content content = host.adaptTo(Content.class);
        return content == null ? null : content.getCreatedBy();
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
