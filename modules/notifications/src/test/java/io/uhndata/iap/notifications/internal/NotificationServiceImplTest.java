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
import io.uhndata.iap.notifications.api.NotificationService;
import io.uhndata.iap.notifications.api.Recipient;
import io.uhndata.iap.notifications.spi.NotificationDelivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NotificationServiceImpl}: that the only judgement it makes is who, and that a channel's
 * answer — including a refusal and a failure — is the channel's own business.
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
    void setUp()
    {
        this.subject = this.context.create().resource("/Submissions/one",
            "title", "A request", "createdBy", CREATOR);
    }

    /** A delivery that accepts everything and remembers what it was given. */
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
        final Field field = NotificationServiceImpl.class.getDeclaredField("deliveries");
        field.setAccessible(true);
        field.set(this.service, List.of(channels));
    }

    // Nothing registered is not a crash; it is a platform with no way to tell anybody anything, which is worth
    // a warning and nothing more
    @Test
    void tellsNobodyWhenNothingCanDeliver()
    {
        assertTrue(this.service.notify(this.notification(), List.of(NotificationService.CREATOR_ROLE)).isEmpty());
    }

    @Test
    void offersTheNotificationToEveryChannel() throws Exception
    {
        // Both, not the first that accepts: somebody may want an email and a marker in the interface, and which
        // combination that is belongs to them
        final Recording first = new Recording(true);
        final Recording second = new Recording(true);
        this.deliveries(first, second);
        this.user(CREATOR, "requester@example.com");

        this.service.notify(this.notification(), List.of(NotificationService.CREATOR_ROLE));

        assertEquals(1, first.told.size());
        assertEquals(1, second.told.size());
        assertEquals(CREATOR, first.told.get(0).userId());
    }

    // Every channel declining is a normal outcome: somebody unreachable is not an error
    @Test
    void reportsNobodyToldWhenEveryChannelDeclines() throws Exception
    {
        this.deliveries(new Recording(false));
        this.user(CREATOR, "requester@example.com");

        assertTrue(this.service.notify(this.notification(), List.of(NotificationService.CREATOR_ROLE)).isEmpty());
    }

    @Test
    void reportsWhoWasActuallyTold() throws Exception
    {
        this.deliveries(new Recording(true));
        this.user(CREATOR, "requester@example.com");

        final List<Recipient> told =
            this.service.notify(this.notification(), List.of(NotificationService.CREATOR_ROLE));

        assertEquals(1, told.size());
        assertEquals("requester@example.com", told.get(0).address());
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
        this.user(CREATOR, "requester@example.com");

        final List<Recipient> told =
            this.service.notify(this.notification(), List.of(NotificationService.CREATOR_ROLE));

        assertEquals(1, told.size());
        assertEquals(1, working.told.size());
    }

    /**
     * Creates an account with an address where the platform looks for one.
     *
     * @param userId the account to create
     * @param address its email address, or {@code null} for an account with none
     * @throws Exception if the account cannot be created
     */
    private void user(final String userId, final String address) throws Exception
    {
        Accounts.create(this.context, userId, address);
    }
}
