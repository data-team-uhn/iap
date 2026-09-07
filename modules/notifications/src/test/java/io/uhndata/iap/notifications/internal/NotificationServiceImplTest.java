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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.Recipient;
import io.uhndata.iap.notifications.spi.NotificationDelivery;
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.principals.internal.CreatorResolver;
import io.uhndata.iap.principals.internal.MeResolver;
import io.uhndata.iap.principals.internal.PrincipalServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NotificationServiceImpl}: the only judgement it makes is who, delivery is observed at the
 * channels rather than reported back, and a channel's failure is the channel's own business.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class NotificationServiceImplTest
{
    private static final String CREATOR = "the-requester";

    // JCR-backed, because who a role names is read through the repository's user manager
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final NotificationServiceImpl service = new NotificationServiceImpl();

    private Resource subject;

    @BeforeEach
    void setUp() throws Exception
    {
        this.subject = this.context.create().resource("/Submissions/one",
            "title", "A request", "createdBy", CREATOR);
        // The real vocabulary, so that the @creator these tests notify resolves the way production resolves it
        final PrincipalServiceImpl principals = new PrincipalServiceImpl();
        inject(PrincipalServiceImpl.class, principals, "resolvers",
            List.of(new CreatorResolver(), new MeResolver()));
        inject(NotificationServiceImpl.class, this.service, "principals", principals);
    }

    /** A delivery that remembers what it was given. */
    private static final class Recording implements NotificationDelivery
    {
        private final List<Recipient> told = new ArrayList<>();

        private final boolean accepts;

        Recording(final boolean accepts)
        {
            this.accepts = accepts;
        }

        @Override
        public boolean deliver(final NotificationContext notification, final Recipient recipient)
        {
            this.told.add(recipient);
            return this.accepts;
        }
    }

    private NotificationContext notification()
    {
        return NotificationContext.about(this.subject).becauseOf("approved").build();
    }

    private void deliveries(final NotificationDelivery... channels) throws Exception
    {
        inject(NotificationServiceImpl.class, this.service, "deliveries", List.of(channels));
    }

    private static void inject(final Class<?> type, final Object target, final String field, final Object value)
        throws Exception
    {
        final Field reference = type.getDeclaredField(field);
        reference.setAccessible(true);
        reference.set(target, value);
    }

    // Nothing registered is not a crash; it is a platform with no way to tell anybody anything, which is worth
    // a warning and nothing more
    @Test
    void tellsNobodyWhenNothingCanDeliver()
    {
        this.service.notify(this.notification(), List.of(PrincipalService.CREATOR));
    }

    @Test
    void offersTheNotificationToEveryChannel() throws Exception
    {
        // Both, not the first that accepts: somebody may want an email and a marker in the interface, and which
        // combination that is belongs to them
        final Recording first = new Recording(true);
        final Recording second = new Recording(true);
        this.deliveries(first, second);
        Accounts.create(this.context, CREATOR, "requester@example.com");

        this.service.notify(this.notification(), List.of(PrincipalService.CREATOR));

        assertEquals(1, first.told.size());
        assertEquals(1, second.told.size());
        assertEquals(CREATOR, first.told.get(0).userId());
        // The account rides along, so a channel can read its own facts off it without rights of its own
        assertNotNull(first.told.get(0).account());
    }

    // A group role reaches each person in it, once, resolved through the same vocabulary the engine reads
    // performers in
    @Test
    void aGroupRoleReachesItsPeopleOnce() throws Exception
    {
        final Recording channel = new Recording(true);
        this.deliveries(channel);
        final var ann = Accounts.create(this.context, "ann", "ann@example.com");
        Accounts.group(this.context, "reviewers", ann);

        // ann is named twice - directly and through the group - and told once
        this.service.notify(this.notification(), List.of("ann", "reviewers"));

        assertEquals(1, channel.told.size());
        assertEquals("ann", channel.told.get(0).userId());
    }

    // Every channel declining is a normal outcome: somebody unreachable is not an error
    @Test
    void carriesOnWhenEveryChannelDeclines() throws Exception
    {
        final Recording declining = new Recording(false);
        this.deliveries(declining);
        Accounts.create(this.context, CREATOR, "requester@example.com");

        this.service.notify(this.notification(), List.of(PrincipalService.CREATOR));

        assertEquals(1, declining.told.size());
    }

    // One channel throwing is not the others' problem, and certainly not the workflow's: the process this
    // reports on has already happened
    @Test
    void carriesOnWhenAChannelThrows() throws Exception
    {
        final Recording working = new Recording(true);
        this.deliveries((notification, recipient) -> {
            throw new IllegalStateException("the mail server is on fire");
        }, working);
        Accounts.create(this.context, CREATOR, "requester@example.com");

        this.service.notify(this.notification(), List.of(PrincipalService.CREATOR));

        assertEquals(1, working.told.size());
    }

    // A subject nothing raised makes @creator name nobody, and nobody is told anything
    @Test
    void aRoleNamingNobodyTellsNobody() throws Exception
    {
        final Recording channel = new Recording(true);
        this.deliveries(channel);
        final Resource orphan = this.context.create().resource("/Submissions/orphan", "title", "Nobody's");

        this.service.notify(NotificationContext.about(orphan).becauseOf("approved").build(),
            List.of(PrincipalService.CREATOR));

        assertTrue(channel.told.isEmpty());
    }
}
