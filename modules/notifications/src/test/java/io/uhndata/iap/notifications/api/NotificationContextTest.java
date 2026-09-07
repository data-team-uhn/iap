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
package io.uhndata.iap.notifications.api;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NotificationContext}: what a notification says about itself.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class NotificationContextTest
{
    private final SlingContext context = new SlingContext();

    private Resource subject;

    @BeforeEach
    void setUp()
    {
        this.subject = this.context.create().resource("/Submissions/one", "title", "A request");
    }

    @Test
    void carriesWhatItWasToldAbout()
    {
        final NotificationContext notification = NotificationContext.about(this.subject)
            .becauseOf("approved")
            .by("an-approver")
            .urgency(NotificationContext.BATCHED)
            .using("/libs/iap/notificationTemplates/approved")
            .with("days", 3)
            .build();

        assertSame(this.subject, notification.getSubject());
        assertEquals("approved", notification.getEvent());
        assertEquals("an-approver", notification.getActor());
        assertEquals(NotificationContext.BATCHED, notification.getUrgency());
        assertEquals("/libs/iap/notificationTemplates/approved", notification.getTemplate());
        assertEquals(3, notification.getVariables().get("days"));
    }

    // Something nobody thought about is more likely to matter than not, so silence means immediate
    @Test
    void isImmediateUnlessToldOtherwise()
    {
        assertEquals(NotificationContext.IMMEDIATE,
            NotificationContext.about(this.subject).build().getUrgency());
        assertEquals(NotificationContext.IMMEDIATE,
            NotificationContext.about(this.subject).urgency(null).build().getUrgency());
        assertEquals(NotificationContext.IMMEDIATE,
            NotificationContext.about(this.subject).urgency("  ").build().getUrgency());
    }

    // Nobody caused a deadline passing, and no template is needed by a delivery that renders nothing
    @Test
    void toleratesHavingNoActorAndNoTemplate()
    {
        final NotificationContext notification = NotificationContext.about(this.subject).build();

        assertNull(notification.getActor());
        assertNull(notification.getTemplate());
        assertEquals("", notification.getEvent());
        assertTrue(notification.getVariables().isEmpty());
    }

    // The variables are read by deliveries that may run later and elsewhere; handing out a live map would let a
    // caller change a message after describing it
    @Test
    void doesNotLetItsVariablesBeChangedAfterwards()
    {
        final NotificationContext notification =
            NotificationContext.about(this.subject).with("days", 3).build();

        assertThrows(UnsupportedOperationException.class, () -> notification.getVariables().put("days", 4));
    }
}
