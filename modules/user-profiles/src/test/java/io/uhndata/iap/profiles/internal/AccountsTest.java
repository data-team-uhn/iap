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
package io.uhndata.iap.profiles.internal;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link Accounts}, covering what it does when the repository is not what it needs. The ordinary paths
 * are exercised through {@link UserProfileServiceImplTest}, which is the only caller.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AccountsTest
{
    private final SlingContext context = new SlingContext();

    private Accounts accounts(final Session session, final boolean sessionless)
    {
        return new Accounts(new TestResolverFactory(this.context.resourceResolver(), session, sessionless));
    }

    @Test
    void findsNobodyWhenTheSessionIsNotJackrabbits() throws Exception
    {
        // Every deployment runs on Oak, so this is a broken installation rather than a supported configuration; it
        // still has to answer rather than fall over, because the profile API turns it into a plain "no such account"
        final Accounts accounts = accounts(mock(Session.class), false);
        final ResourceResolver resolver = accounts.open();

        assertNull(accounts.userManager(resolver));
        assertNull(accounts.find(resolver, "jdoe"));
    }

    @Test
    void refusesToWorkWithoutASession() throws Exception
    {
        final Accounts accounts = accounts(null, true);
        final ResourceResolver resolver = accounts.open();

        assertThrows(RepositoryException.class, () -> accounts.values(resolver));
        assertThrows(RepositoryException.class, () -> accounts.save(resolver));
    }
}
