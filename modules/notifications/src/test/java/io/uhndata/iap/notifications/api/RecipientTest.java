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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Recipient}: whether somebody can be reached, which is a fact about their account rather than
 * about the notification.
 *
 * @version $Id$
 * @since 0.1.0
 */
class RecipientTest
{
    @Test
    void isEmailableWhenTheAccountHasAnAddress()
    {
        assertTrue(new Recipient("jdoe", "J Doe", "jdoe@example.com").isEmailable());
    }

    // Not an error, and not something a caller should have to tell apart from a blank one
    @Test
    void isNotEmailableWithoutAUsableAddress()
    {
        assertFalse(new Recipient("jdoe", "J Doe", null).isEmailable());
        assertFalse(new Recipient("jdoe", "J Doe", "   ").isEmailable());
    }

    // The user id is the durable half: it is what a per-user setting is keyed on, so it is always there even
    // when nothing else about the account is known
    @Test
    void alwaysKnowsWhoItIs()
    {
        final Recipient anonymous = new Recipient("jdoe", null, null);

        assertEquals("jdoe", anonymous.userId());
        assertEquals(null, anonymous.name());
    }
}
