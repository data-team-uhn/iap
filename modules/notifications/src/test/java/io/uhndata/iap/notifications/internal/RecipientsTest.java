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
package io.uhndata.iap.notifications.internal;

import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.notifications.api.Recipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Recipients}: user ids in, people with their accounts out, nobody invented and nobody lost
 * silently.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RecipientsTest
{
    // JCR-backed, because the accounts are read through the repository's user manager
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    @Test
    void findsEachPersonsAccount() throws Exception
    {
        Accounts.create(this.context, "ann", "ann@example.com");
        Accounts.create(this.context, "bob", null);

        final List<Recipient> found =
            Recipients.of(this.context.resourceResolver(), List.of("ann", "bob"));

        assertEquals(2, found.size());
        assertEquals("ann", found.get(0).userId());
        assertNotNull(found.get(0).account());
        assertEquals("bob", found.get(1).userId());
    }

    // An id the repository does not know is skipped with a warning, not an error: a group may well name an
    // account that was removed since
    @Test
    void skipsAnUnknownAccount() throws Exception
    {
        Accounts.create(this.context, "ann", "ann@example.com");

        final List<Recipient> found =
            Recipients.of(this.context.resourceResolver(), List.of("ghost", "ann"));

        assertEquals(1, found.size());
        assertEquals("ann", found.get(0).userId());
    }

    @Test
    void answersNobodyWithoutAUserStore()
    {
        final ResourceResolver bare = Mockito.mock(ResourceResolver.class);
        assertTrue(Recipients.of(bare, List.of("ann")).isEmpty());
    }

    @Test
    void answersNobodyWhenTheUserStoreCannotBeReached() throws Exception
    {
        final JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
        Mockito.when(session.getUserManager()).thenThrow(new RepositoryException("the user store is down"));
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(session);

        assertTrue(Recipients.of(resolver, List.of("ann")).isEmpty());
    }

    @Test
    void skipsAPersonWhoseAccountCannotBeRead() throws Exception
    {
        final JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
        final org.apache.jackrabbit.api.security.user.UserManager users =
            Mockito.mock(org.apache.jackrabbit.api.security.user.UserManager.class);
        Mockito.when(session.getUserManager()).thenReturn(users);
        Mockito.when(users.getAuthorizable("broken")).thenThrow(new RepositoryException("gone"));
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(session);

        assertTrue(Recipients.of(resolver, List.of("broken")).isEmpty());
    }

    // An account with no resource behind it cannot supply anything a delivery reads, so it is skipped rather
    // than handed over half-formed
    @Test
    void skipsAPersonWhoseAccountHasNoResource() throws Exception
    {
        final JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
        final org.apache.jackrabbit.api.security.user.UserManager users =
            Mockito.mock(org.apache.jackrabbit.api.security.user.UserManager.class);
        final org.apache.jackrabbit.api.security.user.User user =
            Mockito.mock(org.apache.jackrabbit.api.security.user.User.class);
        Mockito.when(user.getPath()).thenReturn("/home/users/n/nowhere");
        Mockito.when(session.getUserManager()).thenReturn(users);
        Mockito.when(users.getAuthorizable("nowhere")).thenReturn(user);
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(session);

        assertTrue(Recipients.of(resolver, List.of("nowhere")).isEmpty());
    }
}
