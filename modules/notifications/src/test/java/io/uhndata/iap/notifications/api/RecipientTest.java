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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link Recipient}: identity plus the account, and nothing about any channel.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RecipientTest
{
    private final SlingContext context = new SlingContext();

    @Test
    void carriesTheIdentityAndTheAccount()
    {
        final Resource account = this.context.create().resource("/home/users/j/jdoe");
        final Recipient recipient = new Recipient("jdoe", account);
        assertEquals("jdoe", recipient.userId());
        assertSame(account, recipient.account());
    }

    @Test
    void readsTheNameOffTheAccount()
    {
        final Resource account = this.context.create().resource("/home/users/j/jdoe", "rep:fullname", "J. Doe");
        assertEquals("J. Doe", new Recipient("jdoe", account).name());
    }

    @Test
    void hasNoNameWhenTheAccountDoesNotSay()
    {
        assertNull(new Recipient("jdoe", this.context.create().resource("/home/users/j/jdoe")).name());
    }
}
