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
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.uhndata.iap.utils.UserIds;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

/**
 * Decides whether a sender may act on behalf of someone else, and who the event then counts as.
 *
 * <p>Members of the delegates group may, and so may the administrator, but only for an ordinary user: a group, a
 * service user, a disabled account or the administrator is refused. Whether the person acted for may fire the event
 * at all is still asked of the flow node, as if they had sent it themselves.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Delegation.class)
@Designate(ocd = DelegationConfiguration.class)
public final class Delegation
{
    /** Says whether a user belongs to the delegates group. */
    private final Performers performers;

    /** The principal name of the group whose members may act for others. */
    private volatile String delegates;

    /**
     * Constructor.
     *
     * @param performers says whether a user belongs to the delegates group
     * @param config who may act for others
     */
    @Activate
    public Delegation(@Reference final Performers performers, final DelegationConfiguration config)
    {
        this.performers = performers;
        configure(config);
    }

    /**
     * Takes a changed configuration in place. Restarting instead would restart the engine, which holds this, and
     * the event servlet's registration with it.
     *
     * @param config who may act for others
     */
    @Modified
    void configure(final DelegationConfiguration config)
    {
        this.delegates = config.delegates();
    }

    /**
     * Who an event is sent by, and for, refusing a sender who may not act for the user named.
     *
     * @param serviceResolver the engine's own session, used to look both users up
     * @param senderResolver the session the event was sent through
     * @param onBehalfOf the id of the user it is sent for, or {@code null} when the sender acts for themselves
     * @return the actor the event counts as coming from
     * @throws NotAuthorizedException when the sender may not act for others, or not for this user
     * @throws WorkflowFailedException when the repository cannot say who either of them is
     */
    Actor authorizeActor(final ResourceResolver serviceResolver, final ResourceResolver senderResolver,
        final String onBehalfOf) throws WorkflowException
    {
        // Canonical, because a login resolves case-insensitively and the resolver reports the spelling that was
        // typed: @creator compares against what is recorded, and would otherwise refuse the person who raised the
        // request
        final String sender = UserIds.canonical(senderResolver);
        if (onBehalfOf == null) {
            return Actor.of(sender);
        }
        final User represented = findUser(serviceResolver, onBehalfOf);
        final String id = represented == null ? null : readId(represented);
        // Naming yourself is naming nobody, whoever you are
        if (id != null && id.equals(sender)) {
            return Actor.of(sender);
        }
        if (!isDelegate(serviceResolver, sender) || !mayBeActedFor(represented)) {
            throw new NotAuthorizedException(PerformerCheck.REFUSAL_MESSAGE);
        }
        return new Actor(sender, id);
    }

    /**
     * Whether a user may act on behalf of others: the administrator, or a member of the delegates group.
     *
     * <p>Asked of the user's own session, membership comes from the principals it is bound to, so it needs no read
     * of the user store; only the administrator check looks the user up.</p>
     *
     * @param resolver a session that can say what groups the user belongs to: the engine's, or the user's own
     * @param user the user's canonical id, or {@code null} for nobody signed in
     * @return {@code true} for the administrator or a delegate
     * @throws WorkflowFailedException when the user or their groups cannot be read
     */
    boolean isDelegate(final ResourceResolver resolver, final String user) throws WorkflowFailedException
    {
        if (user == null) {
            return false;
        }
        if (this.performers.isOneOf(user, List.of(this.delegates), resolver)) {
            return true;
        }
        final User found = findUser(resolver, user);
        return found != null && found.isAdmin();
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
     * Finds a user in the repository's user store.
     *
     * @param resolver a session that may read the user store
     * @param id the id to look up, in any case
     * @return the user, or {@code null} when nobody by that id is a user
     * @throws WorkflowFailedException when the user store cannot be read
     */
    private static User findUser(final ResourceResolver resolver, final String id) throws WorkflowFailedException
    {
        final Session session = resolver.adaptTo(Session.class);
        if (!(session instanceof JackrabbitSession)) {
            throw new WorkflowFailedException("The repository cannot be asked who its users are");
        }
        try {
            final Authorizable found = ((JackrabbitSession) session).getUserManager().getAuthorizable(id);
            return found instanceof User ? (User) found : null;
        } catch (final RepositoryException e) {
            throw new WorkflowFailedException("Could not look up a user", e);
        }
    }

    /**
     * Reads a user's canonical id, which a lookup by a differently cased spelling still answers with.
     *
     * @param user the user
     * @return their id
     * @throws WorkflowFailedException when the id cannot be read
     */
    private static String readId(final User user) throws WorkflowFailedException
    {
        try {
            return user.getID();
        } catch (final RepositoryException e) {
            throw new WorkflowFailedException("Could not read the id of the user acted for", e);
        }
    }
}
