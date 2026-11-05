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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final String ADMIN = "admin";

    private final Delegation delegation = EngineFixture.delegation(DELEGATES);

    @Test
    void actsForNobodyElseWithoutOnBehalfOf() throws Exception
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

        final Actor actor = this.delegation.authorizeActor(resolver, sender(ALICE), null);

        assertEquals(ALICE, actor.sender());
        assertNull(actor.onBehalfOf());
        // Nobody needs looking up when nobody else is named
        Mockito.verifyNoInteractions(resolver);
    }

    @Test
    void letsADelegateActForAnOrdinaryUser() throws Exception
    {
        final Actor actor = this.delegation.authorizeActor(repository(), sender(DELEGATE), ALICE);

        assertEquals(DELEGATE, actor.sender());
        assertEquals(ALICE, actor.onBehalfOf());
    }

    @Test
    void recordsThePersonActedForByTheirCanonicalId() throws Exception
    {
        // A lookup resolves case-insensitively, but what gets recorded must match what their own logins record
        assertEquals(ALICE, this.delegation.authorizeActor(repository(), sender(DELEGATE), "ALICE").onBehalfOf());
    }

    @Test
    void letsTheAdministratorActForOthersWhateverTheDelegatesGroup() throws Exception
    {
        assertEquals(ALICE, EngineFixture.delegation("nobody-is-in-this")
            .authorizeActor(repository(), sender(ADMIN), ALICE).onBehalfOf());
    }

    @Test
    void actsForNobodyElseWhenADelegateNamesThemselves() throws Exception
    {
        assertNull(this.delegation.authorizeActor(repository(), sender(DELEGATE), DELEGATE).onBehalfOf());
    }

    @Test
    void actsForNobodyElseWhenSomeoneOutsideTheDelegatesNamesThemselves() throws Exception
    {
        assertNull(this.delegation.authorizeActor(repository(), sender(ALICE), ALICE).onBehalfOf());
    }

    @Test
    void actsForNobodyElseWhenTheAdministratorNamesThemselves() throws Exception
    {
        assertNull(this.delegation.authorizeActor(repository(), sender(ADMIN), ADMIN).onBehalfOf());
    }

    @Test
    void refusesASenderOutsideTheDelegatesGroup()
    {
        assertThrows(NotAuthorizedException.class,
            () -> this.delegation.authorizeActor(repository(), sender("bob"), ALICE));
    }

    @Test
    void refusesAnUnauthenticatedSender()
    {
        assertThrows(NotAuthorizedException.class,
            () -> this.delegation.authorizeActor(repository(), sender(null), ALICE));
    }

    @Test
    void refusesToActForAnyoneButAnOrdinaryEnabledUser()
    {
        // Nobody by that name, a group, the administrator (who passes every check, so acting for them would make
        // any delegate one), a service user, and a disabled account
        assertAll(Stream.of("nobody", "time-off-approvers", ADMIN, "workflows", "departed")
            .map(named -> (Executable) () -> assertThrows(NotAuthorizedException.class,
                () -> this.delegation.authorizeActor(repository(), sender(DELEGATE), named), named)));
    }

    @Test
    void takesAChangedDelegatesGroupWithoutRestarting() throws Exception
    {
        final DelegationConfiguration others = Mockito.mock(DelegationConfiguration.class);
        Mockito.when(others.delegates()).thenReturn("time-off-approvers");

        this.delegation.configure(others);

        assertThrows(NotAuthorizedException.class,
            () -> this.delegation.authorizeActor(repository(), sender(DELEGATE), ALICE));
    }

    @Test
    void failsWhenTheAccountActedForCannotBeRead() throws Exception
    {
        final User broken = user("broken");
        Mockito.when(broken.isDisabled()).thenThrow(new RepositoryException("the account is unreadable"));

        assertThrows(WorkflowFailedException.class,
            () -> this.delegation.authorizeActor(repository(Map.of("broken", broken)), sender(DELEGATE), "broken"));
    }

    @Test
    void failsWhenTheIdActedForCannotBeRead() throws Exception
    {
        final User broken = Mockito.mock(User.class);
        Mockito.when(broken.getID()).thenThrow(new RepositoryException("the id is unreadable"));

        assertThrows(WorkflowFailedException.class,
            () -> this.delegation.authorizeActor(repository(Map.of("broken", broken)), sender(DELEGATE), "broken"));
    }

    @Test
    void failsWhenTheRepositoryCannotSayWhoItsUsersAre()
    {
        final ResourceResolver plain = Mockito.mock(ResourceResolver.class);
        Mockito.when(plain.adaptTo(Session.class)).thenReturn(Mockito.mock(Session.class));

        assertThrows(WorkflowFailedException.class,
            () -> this.delegation.authorizeActor(plain, sender(DELEGATE), ALICE));
    }

    @Test
    void failsWhenTheUserStoreCannotBeRead() throws Exception
    {
        final JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
        Mockito.when(session.getUserManager()).thenThrow(new RepositoryException("the user store is unreadable"));
        final ResourceResolver unreadable = Mockito.mock(ResourceResolver.class);
        Mockito.when(unreadable.adaptTo(Session.class)).thenReturn(session);

        assertThrows(WorkflowFailedException.class,
            () -> this.delegation.authorizeActor(unreadable, sender(DELEGATE), ALICE));
    }

    @Test
    void countsASessionBoundToTheDelegatesAsADelegate() throws Exception
    {
        // A Keycloak role arrives as a bound principal, with no group node to be a member of
        assertTrue(this.delegation.isDelegate(boundTo(DELEGATE, DELEGATES), DELEGATE));
    }

    @Test
    void countsTheAdministratorAsADelegate() throws Exception
    {
        assertTrue(this.delegation.isDelegate(administratorSession(), ADMIN));
    }

    @Test
    void doesNotCountAnyoneElseAsADelegate() throws Exception
    {
        assertFalse(this.delegation.isDelegate(boundTo(ALICE, "time-off-approvers"), ALICE));
    }

    @Test
    void doesNotCountNobodySignedInAsADelegate() throws Exception
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

        assertFalse(this.delegation.isDelegate(resolver, null));
        Mockito.verifyNoInteractions(resolver);
    }

    /**
     * A user's own session, bound to the given principals besides their own, as what they read is served through.
     *
     * @param id the user's id
     * @param bound the principals the session is bound to besides the user's own
     * @return a resolver adapting to that session
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static ResourceResolver boundTo(final String id, final String... bound) throws RepositoryException
    {
        return ownSession(user(id), bound);
    }

    /**
     * The administrator's own session.
     *
     * @return a resolver adapting to that session
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static ResourceResolver administratorSession() throws RepositoryException
    {
        final User admin = user(ADMIN);
        Mockito.when(admin.isAdmin()).thenReturn(true);
        return ownSession(admin);
    }

    /**
     * A user's own session, which can look its own user up.
     *
     * @param user the user
     * @param bound the principals the session is bound to besides the user's own
     * @return a resolver adapting to that session
     * @throws RepositoryException never, but the stubbed methods declare it
     */
    private static ResourceResolver ownSession(final User user, final String... bound) throws RepositoryException
    {
        final String id = user.getID();
        final UserManager users = Mockito.mock(UserManager.class);
        Mockito.when(users.getAuthorizable(id)).thenReturn(user);
        // Built before stubbing: making the principals' own mocks inside a stubbing would interrupt it
        final Set<Principal> principals = Stream.of(bound).map(DelegationTest::principal).collect(Collectors.toSet());
        final JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
        Mockito.when(session.getUserID()).thenReturn(id);
        Mockito.when(session.getUserManager()).thenReturn(users);
        Mockito.when(session.getBoundPrincipals()).thenReturn(principals);
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(session);
        return resolver;
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
