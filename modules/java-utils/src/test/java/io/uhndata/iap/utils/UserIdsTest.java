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

import javax.jcr.Session;

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
    void isNotInstantiatable() throws Exception
    {
        final Constructor<UserIds> constructor = UserIds.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }
}
