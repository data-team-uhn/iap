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
package io.uhndata.iap.principals.internal;

import java.lang.reflect.Field;
import java.security.Principal;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.principal.GroupPrincipal;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.principals.api.PrincipalContext;
import io.uhndata.iap.principals.api.PrincipalLookupException;
import io.uhndata.iap.principals.spi.SpecialNameResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PrincipalServiceImpl}, with the user and principal stores mocked so that both ways the
 * repository stores a group — a local node and a dynamic principal — can be exercised.
 *
 * @version $Id$
 * @since 0.1.0
 */
class PrincipalServiceImplTest
{
    private static final String ALICE = "alice";

    private static final String TEAM = "team";

    private final PrincipalServiceImpl service = new PrincipalServiceImpl();

    private final ResourceResolver resolver = mock(ResourceResolver.class);

    private final JackrabbitSession session = mock(JackrabbitSession.class);

    private final UserManager users = mock(UserManager.class);

    private final PrincipalManager principalManager = mock(PrincipalManager.class);

    private final PrincipalContext context = PrincipalContext.actedBy(ALICE);

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.resolver.adaptTo(Session.class)).thenReturn(this.session);
        when(this.session.getUserManager()).thenReturn(this.users);
        when(this.session.getPrincipalManager()).thenReturn(this.principalManager);
        when(this.session.getUserID()).thenReturn("the-service-user");
    }

    // ---------------------------------------------------------------------------------------------- resolve

    // With nothing registered at all - the whiteboard never bound - literals still pass through
    @Test
    void literalNamesPassThroughUntouched()
    {
        assertEquals(List.of(TEAM, "bob"), this.service.resolve(List.of(TEAM, "bob"), this.context));
    }

    @Test
    void aSpecialNameIsAnsweredByItsResolver() throws Exception
    {
        this.vocabulary(resolver("@several", List.of(ALICE, "bob")));
        assertEquals(List.of(ALICE, "bob", "carol"),
            this.service.resolve(List.of("@several", "carol"), this.context));
    }

    @Test
    void anUnclaimedSpecialNameNamesNobody() throws Exception
    {
        this.vocabulary(resolver("@known", List.of(ALICE)));
        assertEquals(List.of("bob"), this.service.resolve(List.of("@nonsense", "bob"), this.context));
    }

    @Test
    void somebodyNamedTwiceIsNamedOnce() throws Exception
    {
        this.vocabulary(resolver("@several", List.of(ALICE, "bob")));
        assertEquals(List.of(ALICE, "bob"), this.service.resolve(List.of(ALICE, "@several"), this.context));
    }

    // A broken resolver loses its own name's answer, not the whole list's
    @Test
    void aBrokenResolverNamesNobody() throws Exception
    {
        final SpecialNameResolver broken = resolver("@broken", List.of());
        when(broken.resolve(this.context)).thenThrow(new IllegalStateException("boom"));
        this.vocabulary(broken);
        assertEquals(List.of("bob"), this.service.resolve(List.of("@broken", "bob"), this.context));
    }

    // Two resolvers claiming one name is a deployment mistake; one of them answers rather than the call failing
    @Test
    void twoResolversForOneNameDoNotCollide() throws Exception
    {
        this.vocabulary(resolver("@twice", List.of(ALICE)), resolver("@twice", List.of("bob")));
        assertEquals(List.of(ALICE), this.service.resolve(List.of("@twice"), this.context));
    }

    // ---------------------------------------------------------------------------------------- expandToUsers

    @Test
    void aUserExpandsToThemselves() throws Exception
    {
        // Built before the when(), never inside thenReturn: the helper stubs its own mock, and Mockito refuses
        // stubbing nested in an unfinished stubbing
        final Authorizable alice = user(ALICE);
        when(this.users.getAuthorizable(ALICE)).thenReturn(alice);
        assertEquals(List.of(ALICE), this.service.expandToUsers(List.of(ALICE), this.resolver));
    }

    @Test
    void aLocalGroupExpandsToItsPeople() throws Exception
    {
        // getMembers is transitive, so the nested group shows up as its members, not as itself
        final Group team = group(user(ALICE), group(), user("bob"));
        when(this.users.getAuthorizable(TEAM)).thenReturn(team);
        assertEquals(List.of(ALICE, "bob"), this.service.expandToUsers(List.of(TEAM), this.resolver));
    }

    @Test
    void aDynamicGroupExpandsThroughThePrincipalStore() throws Exception
    {
        when(this.users.getAuthorizable("kc-role")).thenReturn(null);
        final GroupPrincipal dynamic = mock(GroupPrincipal.class);
        // doReturn, because members() returns a wildcard the compiler will not let thenReturn infer
        Mockito.doReturn(Collections.enumeration(List.of(name(ALICE), name("gone"), name(TEAM))))
            .when(dynamic).members();
        when(this.principalManager.getPrincipal("kc-role")).thenReturn(dynamic);
        when(this.users.getAuthorizable(Mockito.any(Principal.class))).thenAnswer(call -> {
            final String name = call.getArgument(0, Principal.class).getName();
            // One member the user store no longer knows, one that is itself a group: both contribute nobody
            return switch (name) {
                case ALICE -> user(ALICE);
                case TEAM -> group();
                default -> null;
            };
        });
        assertEquals(List.of(ALICE), this.service.expandToUsers(List.of("kc-role"), this.resolver));
    }

    @Test
    void everyoneExpandsToNobody()
    {
        assertTrue(this.service.expandToUsers(List.of("everyone"), this.resolver).isEmpty());
    }

    @Test
    void anUnknownNameExpandsToNobody() throws Exception
    {
        when(this.users.getAuthorizable("ghost")).thenReturn(null);
        when(this.principalManager.getPrincipal("ghost")).thenReturn(null);
        assertTrue(this.service.expandToUsers(List.of("ghost"), this.resolver).isEmpty());
    }

    // A name behind which the store fails loses its own answer, not the whole list's
    @Test
    void aFailingLookupLosesOnlyItsOwnName() throws Exception
    {
        final Authorizable bob = user("bob");
        when(this.users.getAuthorizable("broken")).thenThrow(new RepositoryException("gone"));
        when(this.users.getAuthorizable("bob")).thenReturn(bob);
        assertEquals(List.of("bob"), this.service.expandToUsers(List.of("broken", "bob"), this.resolver));
    }

    @Test
    void expansionNeedsTheUserStore()
    {
        final ResourceResolver bare = mock(ResourceResolver.class);
        assertThrows(PrincipalLookupException.class,
            () -> this.service.expandToUsers(List.of(TEAM), bare));
    }

    // -------------------------------------------------------------------------------------------- isOneOf

    @Test
    void nobodyIsAmongNoPrincipals()
    {
        assertFalse(this.service.isOneOf(ALICE, List.of(), this.resolver));
    }

    @Test
    void everyoneIsNamedDirectly()
    {
        // Neither answer needs the repository at all, which is why an unstubbed resolver does not fail here
        final ResourceResolver bare = mock(ResourceResolver.class);
        assertTrue(this.service.isOneOf(ALICE, List.of(ALICE), bare));
        assertTrue(this.service.isOneOf(ALICE, List.of("everyone"), bare));
    }

    // Asking about the session's own user reads the bound principals, which carry dynamic memberships too
    @Test
    void theSessionsOwnUserIsAnsweredFromItsBoundPrincipals() throws Exception
    {
        when(this.session.getUserID()).thenReturn(ALICE);
        when(this.session.getBoundPrincipals()).thenReturn(Set.of(name(TEAM)));
        assertTrue(this.service.isOneOf(ALICE, List.of(TEAM), this.resolver));
        assertFalse(this.service.isOneOf(ALICE, List.of("committee"), this.resolver));
    }

    @Test
    void membershipInALocalGroupAdmits() throws Exception
    {
        final Authorizable alice = user(ALICE);
        final Group team = group();
        when(this.users.getAuthorizable(ALICE)).thenReturn(alice);
        when(this.users.getAuthorizable(TEAM)).thenReturn(team);
        when(team.isMember(alice)).thenReturn(true);
        assertTrue(this.service.isOneOf(ALICE, List.of(TEAM), this.resolver));
        when(team.isMember(alice)).thenReturn(false);
        assertFalse(this.service.isOneOf(ALICE, List.of(TEAM), this.resolver));
    }

    @Test
    void membershipInADynamicGroupAdmits() throws Exception
    {
        final Authorizable alice = user(ALICE);
        when(this.users.getAuthorizable(ALICE)).thenReturn(alice);
        when(this.users.getAuthorizable("kc-role")).thenReturn(null);
        final GroupPrincipal dynamic = mock(GroupPrincipal.class);
        when(this.principalManager.getPrincipal("kc-role")).thenReturn(dynamic);
        when(dynamic.isMember(Mockito.any())).thenReturn(true);
        assertTrue(this.service.isOneOf(ALICE, List.of("kc-role"), this.resolver));
        when(dynamic.isMember(Mockito.any())).thenReturn(false);
        assertFalse(this.service.isOneOf(ALICE, List.of("kc-role"), this.resolver));
    }

    @Test
    void otherPeopleAndUnknownNamesDoNotAdmit() throws Exception
    {
        final Authorizable alice = user(ALICE);
        final Authorizable bob = user("bob");
        when(this.users.getAuthorizable(ALICE)).thenReturn(alice);
        // A user named among the principals who is somebody else
        when(this.users.getAuthorizable("bob")).thenReturn(bob);
        // A name neither store knows
        when(this.users.getAuthorizable("ghost")).thenReturn(null);
        when(this.principalManager.getPrincipal("ghost")).thenReturn(null);
        // A name that is a principal, but not a group
        when(this.users.getAuthorizable("flat")).thenReturn(null);
        when(this.principalManager.getPrincipal("flat")).thenReturn(name("flat"));
        assertFalse(this.service.isOneOf(ALICE, List.of("bob", "ghost", "flat"), this.resolver));
    }

    // Fail-closed: an account the repository does not know belongs to nothing
    @Test
    void anUnknownUserBelongsToNothing() throws Exception
    {
        when(this.users.getAuthorizable(ALICE)).thenReturn(null);
        assertFalse(this.service.isOneOf(ALICE, List.of(TEAM), this.resolver));
    }

    @Test
    void aStoreThatCannotAnswerIsNotANo() throws Exception
    {
        when(this.users.getAuthorizable(ALICE)).thenThrow(new RepositoryException("gone"));
        assertThrows(PrincipalLookupException.class,
            () -> this.service.isOneOf(ALICE, List.of(TEAM), this.resolver));
    }

    @Test
    void theCheckNeedsTheUserStore()
    {
        final ResourceResolver bare = mock(ResourceResolver.class);
        when(bare.adaptTo(Session.class)).thenReturn(mock(Session.class));
        assertThrows(PrincipalLookupException.class,
            () -> this.service.isOneOf(ALICE, List.of(TEAM), bare));
    }

    // -------------------------------------------------------------------------------------------- helpers

    private static SpecialNameResolver resolver(final String name, final List<String> answer)
    {
        final SpecialNameResolver resolver = mock(SpecialNameResolver.class);
        when(resolver.getName()).thenReturn(name);
        when(resolver.resolve(Mockito.any())).thenReturn(answer);
        return resolver;
    }

    private void vocabulary(final SpecialNameResolver... resolvers) throws Exception
    {
        final Field field = PrincipalServiceImpl.class.getDeclaredField("resolvers");
        field.setAccessible(true);
        field.set(this.service, List.of(resolvers));
    }

    private static Authorizable user(final String id) throws RepositoryException
    {
        final Authorizable user = mock(Authorizable.class);
        when(user.isGroup()).thenReturn(false);
        when(user.getID()).thenReturn(id);
        when(user.getPrincipal()).thenReturn(name(id));
        return user;
    }

    private static Group group(final Authorizable... members) throws RepositoryException
    {
        final Group group = mock(Group.class);
        when(group.isGroup()).thenReturn(true);
        final Iterator<Authorizable> all = List.of(members).iterator();
        when(group.getMembers()).thenReturn(all);
        return group;
    }

    private static Principal name(final String name)
    {
        return () -> name;
    }
}
