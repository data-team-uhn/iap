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
package io.uhndata.iap.utils;

import java.lang.reflect.Constructor;
import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link UserIds}, whose whole job is to prefer the repository's answer to the login's.
 *
 * @version $Id$
 * @since 0.1.0
 */
class UserIdsTest
{
    @Test
    void answersWithTheRepositorysIdRatherThanTheOneTypedAtLogin()
    {
        // Measured on a running instance: signing in as "Admin" has the resolver report "Admin" while the same
        // request writes jcr:createdBy "admin". The second is the identity; the first is a spelling
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getUserID()).thenReturn("admin");
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.getUserID()).thenReturn("Admin");
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(session);

        assertEquals("admin", UserIds.canonical(resolver));
    }

    @Test
    void fallsBackOnTheResolverWhereThereIsNoRepositoryToAsk()
    {
        // A resolver over another provider has no repository to be canonical about, and the only name available
        // beats no name at all
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.getUserID()).thenReturn("someone");
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(null);

        assertEquals("someone", UserIds.canonical(resolver));
    }

    @Test
    void answersNothingWhenNothingKnowsAUser()
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

        assertNull(UserIds.canonical(resolver));
    }

    @Test
    void answersWithEverythingASessionIsBoundTo() throws Exception
    {
        // The user's own id first, then the principals: a task's performers name principals, so "is this mine"
        // is a question about the whole set rather than about the user id alone
        final JackrabbitSession session = boundTo("priya", "reviewers", "everyone");

        assertEquals(List.of("priya", "reviewers", "everyone"), UserIds.principalsOf(session));
    }

    @Test
    void answersWithJustTheUserWhereTheSessionIsNotJackrabbitsToAsk() throws Exception
    {
        // Never the empty list: a caller filtering on nothing would widen its question to everybody, which is the
        // opposite of asking what is theirs
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getUserID()).thenReturn("priya");

        assertEquals(List.of("priya"), UserIds.principalsOf(session));
    }

    @Test
    void namesEachPrincipalOnce() throws Exception
    {
        // A session is bound to a principal for its own user as well, so the user id would otherwise appear twice
        final JackrabbitSession session = boundTo("priya", "priya", "everyone");

        assertEquals(List.of("priya", "everyone"), UserIds.principalsOf(session));
    }

    /**
     * A Jackrabbit session reporting the given principals.
     *
     * @param userId the user the session belongs to
     * @param principalNames the principals it is bound to
     * @return the mocked session
     * @throws Exception never, but the API being stubbed declares it
     */
    private JackrabbitSession boundTo(final String userId, final String... principalNames) throws Exception
    {
        final JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
        Mockito.when(session.getUserID()).thenReturn(userId);
        final Set<Principal> principals = new LinkedHashSet<>();
        for (final String name : principalNames) {
            principals.add((Principal) () -> name);
        }
        Mockito.when(session.getBoundPrincipals()).thenReturn(principals);
        return session;
    }

    @Test
    void isNotInstantiatable() throws Exception
    {
        final Constructor<UserIds> constructor = UserIds.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }
}
