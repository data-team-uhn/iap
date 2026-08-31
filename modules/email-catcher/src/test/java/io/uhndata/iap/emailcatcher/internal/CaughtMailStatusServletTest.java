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
package io.uhndata.iap.emailcatcher.internal;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.commons.messaging.mail.MailService;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CaughtMailStatusServlet}: whether mail is being caught, and how much of it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CaughtMailStatusServletTest
{
    private static final String HOME = CaughtMailService.CAUGHT_MAIL_PATH;

    private final SlingContext context = new SlingContext();

    private final CaughtMailStatusServlet servlet = new CaughtMailStatusServlet();

    @BeforeEach
    void setUp()
    {
        this.context.create().resource(HOME, "sling:resourceType", "mail/CaughtMailHomepage");
    }

    /** Puts a catcher in place, the way the service registry would while it is switched on. */
    private void catcherIsRegistered() throws ReflectiveOperationException
    {
        final Field field = CaughtMailStatusServlet.class.getDeclaredField("catcher");
        field.setAccessible(true);
        field.set(this.servlet, Mockito.mock(MailService.class));
    }

    /**
     * Files one message, as the catcher would have.
     *
     * @param name the node name
     */
    private void caught(final String name)
    {
        this.context.create().resource(HOME + "/" + name, Map.of(
            "jcr:primaryType", CaughtMailService.MESSAGE_TYPE,
            "subject", "A message",
            "caughtAt", Calendar.getInstance()));
    }

    /** The servlet's answer, parsed. */
    private JsonObject read() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource(HOME));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        this.servlet.doGet(request, response);
        return Json.createReader(new StringReader(response.getOutputAsString())).readObject();
    }

    /**
     * An instance that is not catching says so. It matters that this is distinguishable from an empty mailbox:
     * the two look identical in a count and mean opposite things.
     */
    @Test
    void saysCatchingIsOffWhenNoCatcherIsRegistered() throws IOException
    {
        final JsonObject answer = this.read();

        assertFalse(answer.getBoolean("enabled"));
        assertEquals(0, answer.getInt("total"));
    }

    @Test
    void saysCatchingIsOnWhenOneIs() throws Exception
    {
        this.catcherIsRegistered();

        assertTrue(this.read().getBoolean("enabled"));
    }

    @Test
    void countsWhatHasBeenCaught() throws Exception
    {
        this.catcherIsRegistered();
        this.caught("one");
        this.caught("two");

        assertEquals(2, this.read().getInt("total"));
    }

    /**
     * The access control policy protecting the folder is a child of it too, so a reader counting children would
     * report a message before anything had been sent.
     */
    @Test
    void countsOnlyMessages() throws IOException
    {
        this.context.create().resource(HOME + "/rep:policy", "jcr:primaryType", "rep:ACL");
        this.caught("one");

        assertEquals(1, this.read().getInt("total"));
    }
}
