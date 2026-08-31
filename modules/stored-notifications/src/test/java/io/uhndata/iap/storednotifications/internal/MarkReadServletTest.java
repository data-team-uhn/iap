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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MarkReadServlet}: the repository decides who may flip the marker, and the servlet only carries
 * the answer.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MarkReadServletTest
{
    private final SlingContext context = new SlingContext();

    private final MarkReadServlet servlet = new MarkReadServlet();

    @Test
    void marksItRead() throws IOException
    {
        final Resource notification = this.notification();

        final MockSlingJakartaHttpServletResponse response = this.post(notification);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertTrue(response.getOutputAsString().contains("ok"));
        assertEquals(Boolean.TRUE, notification.getValueMap().get(StoredNotifications.READ, Boolean.class));
    }

    // Reading twice is not an event
    @Test
    void markingItAgainIsFine() throws IOException
    {
        final Resource notification = this.notification();

        this.post(notification);
        final MockSlingJakartaHttpServletResponse response = this.post(notification);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertEquals(Boolean.TRUE, notification.getValueMap().get(StoredNotifications.READ, Boolean.class));
    }

    // The repository said no - a session with no write on the node gets no writable view of it - and the servlet
    // carries that answer rather than second-guessing it
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

    @Test
    void reportsAWriteThatCouldNotBeSaved() throws IOException
    {
        final Resource notification = this.notification();
        final ResourceResolver failing = new ResourceResolverWrapper(this.context.resourceResolver())
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
            StoredNotifications.RECIPIENT, "the-requester",
            StoredNotifications.LINE, "It happened",
            StoredNotifications.READ, Boolean.FALSE);
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
