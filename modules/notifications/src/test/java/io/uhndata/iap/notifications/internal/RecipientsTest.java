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
import javax.jcr.Value;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.notifications.api.NotificationService;
import io.uhndata.iap.notifications.api.Recipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Recipients}: turning the roles a workflow names into the people they mean.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RecipientsTest
{
    private static final String CREATOR = "the-requester";

    private static final String CREATOR_EMAIL = "requester@example.com";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private Resource subject;

    @BeforeEach
    void setUp()
    {
        this.subject = this.context.create().resource("/Submissions/one",
            "title", "A request", "createdBy", CREATOR);
    }

    private List<Recipient> resolve(final String... roles)
    {
        return Recipients.of(this.context.resourceResolver(), this.subject, List.of(roles));
    }

    // @creator is read from what the engine recorded, not from jcr:createdBy, which names the engine's own
    // service user for everything it writes
    @Test
    void findsWhoeverRaisedTheSubject() throws Exception
    {
        Accounts.create(this.context, CREATOR, CREATOR_EMAIL);

        final List<Recipient> found = this.resolve(NotificationService.CREATOR_ROLE);

        assertEquals(1, found.size());
        assertEquals(CREATOR, found.get(0).userId());
        assertEquals(CREATOR_EMAIL, found.get(0).address());
    }

    @Test
    void findsTheAddressWhereKeycloakPutsIt() throws Exception
    {
        Accounts.create(this.context, CREATOR, CREATOR_EMAIL);

        assertEquals(CREATOR_EMAIL, this.resolve(NotificationService.CREATOR_ROLE).get(0).address());
        assertEquals("profile/email", Recipients.EMAIL_PROPERTY);
    }

    // Somebody with no address is still somebody to tell: another delivery may reach them
    @Test
    void reportsAnAccountWithNoAddress() throws Exception
    {
        Accounts.create(this.context, CREATOR, null);

        final Recipient found = this.resolve(NotificationService.CREATOR_ROLE).get(0);

        assertNull(found.address());
        assertEquals(CREATOR, found.userId());
    }

    @Test
    void expandsAGroupToItsMembers() throws Exception
    {
        Accounts.group(this.context, "approvers",
            Accounts.create(this.context, "ann", "ann@example.com"),
            Accounts.create(this.context, "bob", "bob@example.com"));

        assertEquals(List.of("ann", "bob"),
            this.resolve("approvers").stream().map(Recipient::userId).sorted().toList());
    }

    // A group of groups is still a group of people, and somebody in one should be told either way
    @Test
    void expandsNestedGroupsToo() throws Exception
    {
        final var inner = Accounts.group(this.context, "leads", Accounts.create(this.context, "ann", "a@x.com"));
        final var outer = Accounts.group(this.context, "everyone-here");
        outer.addMember(inner);
        this.context.resourceResolver().commit();

        assertEquals(List.of("ann"), this.resolve("everyone-here").stream().map(Recipient::userId).toList());
    }

    @Test
    void namesAUserDirectly() throws Exception
    {
        Accounts.create(this.context, "ann", "ann@example.com");

        assertEquals(List.of("ann"), this.resolve("ann").stream().map(Recipient::userId).toList());
    }

    // Somebody named twice — once themselves and once through a group — is told once
    @Test
    void tellsEachPersonOnlyOnce() throws Exception
    {
        Accounts.group(this.context, "approvers", Accounts.create(this.context, "ann", "ann@example.com"));

        assertEquals(1, this.resolve("ann", "approvers").size());
    }

    // A definition's own order is what a reader sees
    @Test
    void keepsTheOrderTheRolesWereGivenIn() throws Exception
    {
        Accounts.create(this.context, CREATOR, CREATOR_EMAIL);
        Accounts.create(this.context, "ann", "ann@example.com");

        assertEquals(List.of("ann", CREATOR),
            this.resolve("ann", NotificationService.CREATOR_ROLE).stream().map(Recipient::userId).toList());
    }

    // A role naming nobody is a definition worth a warning, not a failure: the process carries on
    @Test
    void passesOverARoleThatNamesNobody()
    {
        assertTrue(this.resolve("nobody-by-that-name").isEmpty());
    }

    @Test
    void passesOverASubjectThatSaysNothingAboutItsCreator()
    {
        this.subject = this.context.create().resource("/Submissions/anonymous", "title", "No creator");

        assertTrue(this.resolve(NotificationService.CREATOR_ROLE).isEmpty());
    }

    // An account may carry the property with nothing in it, which is not an address
    @Test
    void passesOverAnEmptyAddressProperty() throws Exception
    {
        Accounts.create(this.context, CREATOR, null).setProperty(Recipients.EMAIL_PROPERTY, new Value[0]);
        this.context.resourceResolver().commit();

        assertNull(this.resolve(NotificationService.CREATOR_ROLE).get(0).address());
    }

    // Notifications need a Jackrabbit session to find out who to tell; without one nobody is told, rather than
    // the process failing over something that is not its fault
    @Test
    void tellsNobodyWithoutAJackrabbitSession()
    {
        final SlingContext plain = new SlingContext();
        final Resource elsewhere = plain.create().resource("/Submissions/two", "createdBy", CREATOR);

        assertTrue(Recipients.of(plain.resourceResolver(), elsewhere,
            List.of(NotificationService.CREATOR_ROLE)).isEmpty());
    }

    @Test
    void tellsNobodyWhenTheUserManagerCannotBeReached() throws Exception
    {
        assertTrue(Recipients.of(brokenResolver(true), this.subject,
            List.of(NotificationService.CREATOR_ROLE)).isEmpty());
    }

    // A repository that cannot answer who a role names is worth a warning; the process carries on
    @Test
    void passesOverARoleTheRepositoryCannotAnswerFor() throws Exception
    {
        assertTrue(Recipients.of(brokenResolver(false), this.subject, List.of("approvers")).isEmpty());
    }

    @Test
    void passesOverAnAccountItCannotRead() throws Exception
    {
        assertTrue(Recipients.of(brokenResolver(false), this.subject,
            List.of(NotificationService.CREATOR_ROLE)).isEmpty());
    }

    /**
     * A resolver whose repository refuses to answer.
     *
     * @param atTheUserManager {@code true} to fail when the user manager is asked for, {@code false} to fail
     *            when an account is looked up
     * @return the resolver
     * @throws Exception if the mocks cannot be built
     */
    private static ResourceResolver brokenResolver(final boolean atTheUserManager) throws Exception
    {
        final JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
        if (atTheUserManager) {
            Mockito.when(session.getUserManager()).thenThrow(new RepositoryException("no user manager"));
        } else {
            final UserManager users = Mockito.mock(UserManager.class);
            Mockito.when(users.getAuthorizable(Mockito.anyString()))
                .thenThrow(new RepositoryException("cannot read"));
            Mockito.when(session.getUserManager()).thenReturn(users);
        }
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(session);
        return resolver;
    }

    // The creator's account may have been removed since they raised it
    @Test
    void passesOverACreatorWithNoAccount()
    {
        assertTrue(this.resolve(NotificationService.CREATOR_ROLE).isEmpty());
    }
}
