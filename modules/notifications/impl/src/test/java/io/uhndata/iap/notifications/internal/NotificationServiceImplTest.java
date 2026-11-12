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

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
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
 * Tests for {@link NotificationServiceImpl}. Recording deliveries show who was offered each notification.
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

    private final ErrorLoggerService errors = Mockito.mock(ErrorLoggerService.class);

    private Resource subject;

    @BeforeEach
    void setUp()
    {
        this.subject = this.context.create().resource("/Submissions/one",
            "title", "A request", "createdBy", CREATOR);
        // The real principals service, with the resolvers of @creator and @me
        this.context.registerInjectActivateService(new CreatorResolver());
        this.context.registerInjectActivateService(new MeResolver());
        this.context.registerInjectActivateService(new PrincipalServiceImpl());
        ErrorLogger.setService(this.errors);
    }

    @AfterEach
    void tearDown()
    {
        ErrorLogger.unsetService(this.errors);
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

    /** The service under test, wired to the given channels as the only registered deliveries. */
    private NotificationServiceImpl deliveringThrough(final NotificationDelivery... channels)
    {
        for (final NotificationDelivery channel : channels) {
            this.context.registerService(NotificationDelivery.class, channel);
        }
        return this.context.registerInjectActivateService(new NotificationServiceImpl());
    }

    // The requester has an account, so only the missing channel stands between them and the news
    @Test
    void tellsNobodyWhenNothingCanDeliver() throws Exception
    {
        Accounts.create(this.context, CREATOR);

        this.deliveringThrough().notify(this.notification(), List.of(PrincipalService.CREATOR_SPECIAL_NAME));

        Mockito.verify(this.errors).logProblem(ArgumentMatchers.contains("nobody can be told"),
            ArgumentMatchers.any());
    }

    @Test
    void offersTheNotificationToEveryChannel() throws Exception
    {
        final Recording first = new Recording(true);
        final Recording second = new Recording(true);
        final NotificationServiceImpl service = this.deliveringThrough(first, second);
        Accounts.create(this.context, CREATOR);

        service.notify(this.notification(), List.of(PrincipalService.CREATOR_SPECIAL_NAME));

        assertEquals(1, first.told.size());
        assertEquals(1, second.told.size());
        assertEquals(CREATOR, first.told.get(0).userId());
        assertNotNull(first.told.get(0).account());
    }

    @Test
    void aGroupRoleReachesItsPeopleOnce() throws Exception
    {
        final Recording channel = new Recording(true);
        final NotificationServiceImpl service = this.deliveringThrough(channel);
        final var ann = Accounts.create(this.context, "ann");
        Accounts.group(this.context, "reviewers", ann);

        // ann is named twice, directly and through the group, and told once
        service.notify(this.notification(), List.of("ann", "reviewers"));

        assertEquals(1, channel.told.size());
        assertEquals("ann", channel.told.get(0).userId());
    }

    @Test
    void carriesOnWhenEveryChannelDeclines() throws Exception
    {
        final Recording declining = new Recording(false);
        final NotificationServiceImpl service = this.deliveringThrough(declining);
        Accounts.create(this.context, CREATOR);

        service.notify(this.notification(), List.of(PrincipalService.CREATOR_SPECIAL_NAME));

        assertEquals(1, declining.told.size());
    }

    @Test
    void carriesOnWhenAChannelThrows() throws Exception
    {
        final Recording working = new Recording(true);
        final NotificationServiceImpl service = this.deliveringThrough((notification, recipient) -> {
            throw new IllegalStateException("the mail server is on fire");
        }, working);
        Accounts.create(this.context, CREATOR);

        service.notify(this.notification(), List.of(PrincipalService.CREATOR_SPECIAL_NAME));

        assertEquals(1, working.told.size());
    }

    // The subject has no createdBy, so @creator names nobody
    @Test
    void aRoleNamingNobodyTellsNobody() throws Exception
    {
        final Recording channel = new Recording(true);
        final NotificationServiceImpl service = this.deliveringThrough(channel);
        final Resource orphan = this.context.create().resource("/Submissions/orphan", "title", "Nobody's");

        service.notify(NotificationContext.about(orphan).becauseOf("approved").build(),
            List.of(PrincipalService.CREATOR_SPECIAL_NAME));

        assertTrue(channel.told.isEmpty());
    }
}
