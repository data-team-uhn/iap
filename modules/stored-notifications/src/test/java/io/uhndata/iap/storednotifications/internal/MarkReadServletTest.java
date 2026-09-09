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
package io.uhndata.iap.storednotifications.internal;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.storednotifications.api.StoredNotifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MarkReadServlet}. Both halves of "may they?" are exercised: what access control says, and
 * whether the notification is the caller's at all.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MarkReadServletTest
{
    private static final String RECIPIENT = "the-requester";

    private final SlingContext context = new SlingContext();

    private final MarkReadServlet servlet = new MarkReadServlet();

    @Test
    void marksItRead() throws IOException
    {
        final Resource notification = this.notification();

        final MockSlingJakartaHttpServletResponse response = this.post(this.readBy(notification, RECIPIENT));

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertTrue(response.getOutputAsString().contains("ok"));
        assertEquals(Boolean.TRUE, notification.getValueMap().get(StoredNotifications.READ, Boolean.class));
    }

    // Reading twice is not an event
    @Test
    void markingItAgainIsFine() throws IOException
    {
        final Resource notification = this.readBy(this.notification(), RECIPIENT);

        this.post(notification);
        final MockSlingJakartaHttpServletResponse response = this.post(notification);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertEquals(Boolean.TRUE, notification.getValueMap().get(StoredNotifications.READ, Boolean.class));
    }

    // A session with no write on the node gets no writable view of it. The servlet carries that answer
    // rather than second-guessing it
    @Test
    void refusesWhoeverTheRepositoryRefuses() throws IOException
    {
        final Resource unwritable = new ResourceWrapper(this.notification())
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == ModifiableValueMap.class ? null : super.adaptTo(type);
            }
        };

        final MockSlingJakartaHttpServletResponse response = this.post(unwritable);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        assertTrue(response.getOutputAsString().contains("error"));
    }

    // The case access control cannot catch: this session may write the node and still has no business
    // marking it, which is every administrator's session
    @Test
    void refusesSomebodyElsesNotificationTheyCanWrite() throws IOException
    {
        final Resource notification = this.notification();

        final MockSlingJakartaHttpServletResponse response =
            this.post(this.readBy(notification, "an-administrator"));

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        assertNotNull(notification.adaptTo(ModifiableValueMap.class), "the write itself was allowed");
        assertEquals(Boolean.FALSE, notification.getValueMap().get(StoredNotifications.READ, Boolean.class));
    }

    // Nobody's to mark, which is what a notification with no recipient recorded on it would be
    @Test
    void refusesANotificationThatNamesNobody() throws IOException
    {
        final Resource nobodys = this.context.create().resource("/Notifications/aa/bb/cc/two",
            "sling:resourceType", StoredNotifications.RESOURCE_TYPE,
            StoredNotifications.LINE, "It happened",
            StoredNotifications.READ, Boolean.FALSE);

        final MockSlingJakartaHttpServletResponse response = this.post(this.readBy(nobodys, RECIPIENT));

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    @Test
    void reportsAWriteThatCouldNotBeSaved() throws IOException
    {
        final Resource notification = this.notification();
        final ResourceResolver failing = new ResourceResolverWrapper(this.sessionOf(RECIPIENT))
        {
            @Override
            public void commit() throws PersistenceException
            {
                throw new PersistenceException("the disk is full");
            }
        };
        final Resource onFailingSession = new ResourceWrapper(notification)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return failing;
            }
        };

        final MockSlingJakartaHttpServletResponse response = this.post(onFailingSession);

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
        assertTrue(response.getOutputAsString().contains("error"));
    }

    private Resource notification()
    {
        return this.context.create().resource("/Notifications/aa/bb/cc/one",
            "sling:resourceType", StoredNotifications.RESOURCE_TYPE,
            StoredNotifications.RECIPIENT, RECIPIENT,
            StoredNotifications.LINE, "It happened",
            StoredNotifications.READ, Boolean.FALSE);
    }

    /** The mock resolver reports no user of its own, so who is asking is said here. */
    private ResourceResolver sessionOf(final String userId)
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public String getUserID()
            {
                return userId;
            }
        };
    }

    private Resource readBy(final Resource target, final String userId)
    {
        final ResourceResolver session = this.sessionOf(userId);
        return new ResourceWrapper(target)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return session;
            }
        };
    }

    private MockSlingJakartaHttpServletResponse post(final Resource target) throws IOException
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(target);
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        this.servlet.doPost(request, response);
        return response;
    }
}
