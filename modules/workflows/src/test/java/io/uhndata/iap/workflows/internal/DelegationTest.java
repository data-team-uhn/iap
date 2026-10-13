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

import java.security.Principal;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.principal.GroupPrincipal;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link Delegation}. A delegate acting for someone gets past every later check as that person, so
 * the refusals matter most.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DelegationTest
{
    private static final String DELEGATES = "iap-administrators";

    private static final String DELEGATE = "assistant";

    private static final String ALICE = "alice";

    private final Delegation delegation = EngineFixture.delegation(DELEGATES);

    @Test
    void actsForNobodyElseWithoutOnBehalfOf() throws Exception
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

        final Actor actor = this.delegation.actor(resolver, sender(ALICE), null);

        assertEquals(ALICE, actor.id());
        assertNull(actor.onBehalfOf());
        // Nobody needs looking up when nobody else is named
        Mockito.verifyNoInteractions(resolver);
    }

    @Test
    void letsADelegateActForAnOrdinaryUser() throws Exception
    {
        final Actor actor = this.delegation.actor(repository(), sender(DELEGATE), ALICE);

        assertEquals(DELEGATE, actor.id());
        assertEquals(ALICE, actor.onBehalfOf());
    }

    @Test
    void recordsThePersonActedForByTheirCanonicalId() throws Exception
    {
        // A lookup resolves case-insensitively, but what gets recorded must match what their own logins record
        assertEquals(ALICE, this.delegation.actor(repository(), sender(DELEGATE), "ALICE").onBehalfOf());
    }

    @Test
    void letsTheAdministratorActForOthersWhateverTheDelegatesGroup() throws Exception
    {
        assertEquals(ALICE,
            EngineFixture.delegation("nobody-is-in-this").actor(repository(), sender("admin"), ALICE).onBehalfOf());
    }

    @Test
    void actsForNobodyElseWhenADelegateNamesThemselves() throws Exception
    {
        assertNull(this.delegation.actor(repository(), sender(DELEGATE), DELEGATE).onBehalfOf());
    }

    @Test
    void refusesASenderOutsideTheDelegatesGroup()
    {
        assertThrows(NotAuthorizedException.class, () -> this.delegation.actor(repository(), sender("bob"), ALICE));
    }

    @Test
    void refusesAnUnauthenticatedSender()
    {
        assertThrows(NotAuthorizedException.class, () -> this.delegation.actor(repository(), sender(null), ALICE));
    }

    @Test
    void refusesToActForSomeoneTheRepositoryHasNeverHeardOf()
    {
        assertThrows(NotAuthorizedException.class,
            () -> this.delegation.actor(repository(), sender(DELEGATE), "nobody"));
    }

    @Test
    void refusesToActForAGroup()
    {
        assertThrows(NotAuthorizedException.class,
            () -> this.delegation.actor(repository(), sender(DELEGATE), "time-off-approvers"));
    }

    @Test
    void refusesToActForAnAdministrator()
    {
        // The administrator passes every check, so acting for them would make any delegate one
        assertThrows(NotAuthorizedException.class,
            () -> this.delegation.actor(repository(), sender(DELEGATE), "admin"));
    }

    @Test
    void refusesToActForAServiceUser()
    {
        assertThrows(NotAuthorizedException.class,
            () -> this.delegation.actor(repository(), sender(DELEGATE), "workflows"));
    }

    @Test
    void refusesToActForADisabledAccount()
    {
        assertThrows(NotAuthorizedException.class,
            () -> this.delegation.actor(repository(), sender(DELEGATE), "departed"));
    }

    @Test
    void failsWhenTheAccountActedForCannotBeRead() throws Exception
    {
        final User broken = user("broken");
        Mockito.when(broken.isDisabled()).thenThrow(new RepositoryException("the account is unreadable"));

        assertThrows(WorkflowFailedException.class,
            () -> this.delegation.actor(repository(Map.of("broken", broken)), sender(DELEGATE), "broken"));
    }

    @Test
    void failsWhenTheIdActedForCannotBeRead() throws Exception
    {
        final User broken = Mockito.mock(User.class);
        Mockito.when(broken.getID()).thenThrow(new RepositoryException("the id is unreadable"));

        assertThrows(WorkflowFailedException.class,
            () -> this.delegation.actor(repository(Map.of("broken", broken)), sender(DELEGATE), "broken"));
    }

    /**
     * The session an event was sent through.
     *
     * @param id the user id it reports, or {@code null} for an unauthenticated one
     * @return a stub resolver
     */
    private static ResourceResolver sender(final String id)
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.getUserID()).thenReturn(id);
        return resolver;
    }

    /**
     * The standard cast: a delegate, an outsider, the administrator, and the kinds of account nobody may act for.
     *
     * @return a resolver adapting to that repository
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static ResourceResolver repository() throws RepositoryException
    {
        return repository(Map.of());
    }

    /**
     * The standard cast, plus some extra authorizables.
     *
     * @param extra more authorizables by the id they are looked up with
     * @return a resolver adapting to that repository
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static ResourceResolver repository(final Map<String, Authorizable> extra) throws RepositoryException
    {
        final User alice = user(ALICE);
        final User admin = user("admin");
        Mockito.when(admin.isAdmin()).thenReturn(true);
        final User service = user("workflows");
        Mockito.when(service.isSystemUser()).thenReturn(true);
        final User departed = user("departed");
        Mockito.when(departed.isDisabled()).thenReturn(true);
        final Group group = Mockito.mock(Group.class);
        Mockito.when(group.getID()).thenReturn("time-off-approvers");

        final UserManager userManager = Mockito.mock(UserManager.class);
        Mockito.when(userManager.getAuthorizable(Mockito.anyString())).thenAnswer(invocation ->
            extra.get(invocation.getArgument(0, String.class)));
        for (final Authorizable known : List.of(user(DELEGATE), user("bob"), admin, service, departed, group)) {
            Mockito.when(userManager.getAuthorizable(known.getID())).thenReturn(known);
        }
        Mockito.when(userManager.getAuthorizable(ALICE)).thenReturn(alice);
        Mockito.when(userManager.getAuthorizable("ALICE")).thenReturn(alice);

        // A synchronised group with no node behind it, as a Keycloak role is
        final GroupPrincipal delegates = Mockito.mock(GroupPrincipal.class);
        Mockito.when(delegates.isMember(Mockito.any())).thenAnswer(invocation ->
            DELEGATE.equals(invocation.getArgument(0, Principal.class).getName()));
        final PrincipalManager principals = Mockito.mock(PrincipalManager.class);
        Mockito.when(principals.getPrincipal(DELEGATES)).thenReturn(delegates);
        final JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
        Mockito.when(session.getUserManager()).thenReturn(userManager);
        Mockito.when(session.getPrincipalManager()).thenReturn(principals);
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(session);
        return resolver;
    }

    /**
     * An ordinary, enabled user.
     *
     * @param id the user's id, also their principal name
     * @return a stub user
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static User user(final String id) throws RepositoryException
    {
        final Principal principal = principal(id);
        final User user = Mockito.mock(User.class);
        Mockito.when(user.getID()).thenReturn(id);
        Mockito.when(user.getPrincipal()).thenReturn(principal);
        return user;
    }

    /**
     * A principal that knows its own name.
     *
     * @param name the principal name
     * @return a stub principal
     */
    private static Principal principal(final String name)
    {
        final Principal principal = Mockito.mock(Principal.class);
        Mockito.when(principal.getName()).thenReturn(name);
        return principal;
    }
}
