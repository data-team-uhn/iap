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

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.Designate;

import io.uhndata.iap.utils.UserIds;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

/**
 * Decides whether a sender may act on behalf of someone else, and who the event then counts as.
 *
 * <p>Only members of the delegates group may, and only for an ordinary user: not a group, a service user, a disabled
 * account or an administrator, who passes every check and would turn a delegate into one. Whether the person acted
 * for may fire the event at all is still asked of the flow node, as if they had sent it themselves.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Delegation.class)
@Designate(ocd = DelegationConfiguration.class)
public final class Delegation
{
    /** The principal name of the group whose members may act for others. */
    private final String delegates;

    /**
     * Constructor.
     *
     * @param config who may act for others
     */
    @Activate
    public Delegation(final DelegationConfiguration config)
    {
        this.delegates = config.delegates();
    }

    /**
     * Who an event is sent by, and for.
     *
     * @param serviceResolver the engine's own session, used to look both users up
     * @param senderResolver the session the event was sent through
     * @param onBehalfOf the id of the user it is sent for, or {@code null} when the sender acts for themselves
     * @return the actor the event counts as coming from
     * @throws NotAuthorizedException when the sender may not act for others, or not for this user
     * @throws WorkflowFailedException when the repository cannot say who either of them is
     */
    Actor actor(final ResourceResolver serviceResolver, final ResourceResolver senderResolver,
        final String onBehalfOf) throws WorkflowException
    {
        // Canonical, because a login resolves case-insensitively and the resolver reports the spelling that was
        // typed: @creator compares against what is recorded, and would otherwise refuse the person who raised the
        // request
        final String sender = UserIds.canonical(senderResolver);
        if (onBehalfOf == null) {
            return Actor.of(sender);
        }
        if (!PerformerCheck.of(serviceResolver, sender).admits(List.of(this.delegates))) {
            throw new NotAuthorizedException(PerformerCheck.REFUSAL_MESSAGE);
        }
        final User represented = PerformerCheck.of(serviceResolver, onBehalfOf).user();
        if (!mayBeActedFor(represented)) {
            throw new NotAuthorizedException(PerformerCheck.REFUSAL_MESSAGE);
        }
        final String id = id(represented);
        return id.equals(sender) ? Actor.of(sender) : new Actor(sender, id);
    }

    /**
     * Whether whoever is behind a session may act for others. For deciding what to show them only: {@link #actor}
     * still decides what they may do.
     *
     * @param session the reader's own session
     * @return {@code true} for a member of the delegates group, or the administrator
     * @throws RepositoryException when the session cannot say who it is
     */
    boolean isDelegate(final Session session) throws RepositoryException
    {
        return UserIds.principalsOf(session).contains(this.delegates) || isAdministrator(session);
    }

    /**
     * Whether a session belongs to the administrator.
     *
     * @param session the session
     * @return {@code true} for the administrator
     * @throws RepositoryException when the session cannot look its own user up
     */
    private static boolean isAdministrator(final Session session) throws RepositoryException
    {
        final Authorizable self = session instanceof JackrabbitSession
            ? ((JackrabbitSession) session).getUserManager().getAuthorizable(session.getUserID())
            : null;
        return self instanceof User && ((User) self).isAdmin();
    }

    /**
     * Whether a user is one somebody else may act for.
     *
     * @param user the user named, or {@code null} when nobody by that name is a user
     * @return {@code true} for an enabled, ordinary user
     * @throws WorkflowFailedException when the user's account cannot be read
     */
    private static boolean mayBeActedFor(final User user) throws WorkflowFailedException
    {
        if (user == null || user.isAdmin() || user.isSystemUser()) {
            return false;
        }
        try {
            return !user.isDisabled();
        } catch (final RepositoryException e) {
            throw new WorkflowFailedException("Could not read the account of the user acted for", e);
        }
    }

    /**
     * A user's canonical id, which a lookup by a differently cased spelling still answers with.
     *
     * @param user the user
     * @return their id
     * @throws WorkflowFailedException when the id cannot be read
     */
    private static String id(final User user) throws WorkflowFailedException
    {
        try {
            return user.getID();
        } catch (final RepositoryException e) {
            throw new WorkflowFailedException("Could not read the id of the user acted for", e);
        }
    }
}
