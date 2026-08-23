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
import java.util.Calendar;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.utils.DateUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CaughtMailServlet}: that what was caught can be read back in one request, newest first.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CaughtMailServletTest
{
    private static final String HOME = CaughtMailService.CAUGHT_MAIL_PATH;

    private final SlingContext context = new SlingContext();

    private final CaughtMailServlet servlet = new CaughtMailServlet();

    @BeforeEach
    void setUp()
    {
        this.context.create().resource(HOME, "sling:resourceType", "mail/CaughtMailHomepage");
    }

    /**
     * Files one message, as the catcher would have.
     *
     * @param name the node name
     * @param subject what it says
     * @param minutesAgo how long ago it was caught
     */
    private void caught(final String name, final String subject, final int minutesAgo)
    {
        final Calendar when = Calendar.getInstance();
        when.add(Calendar.MINUTE, -minutesAgo);
        this.context.create().resource(HOME + "/" + name, Map.of(
            "jcr:primaryType", CaughtMailService.MESSAGE_TYPE,
            "subject", subject,
            "caughtAt", when,
            "from", new String[] { "sender@example.com" },
            "to", new String[] { "recipient@example.com" },
            "textBody", "A body"));
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

    @Test
    void answersWithNothingWhenNothingHasBeenSent() throws IOException
    {
        final JsonObject answer = this.read();

        assertEquals(0, answer.getInt("total"));
        assertTrue(answer.getJsonArray("messages").isEmpty());
    }

    @Test
    void describesWhatWasCaught() throws IOException
    {
        this.caught("one", "A subject", 0);

        final JsonObject message = this.read().getJsonArray("messages").getJsonObject(0);

        assertEquals("A subject", message.getString("subject"));
        assertEquals("A body", message.getString("textBody"));
        assertEquals(HOME + "/one", message.getString("path"));
        assertEquals("sender@example.com", message.getJsonArray("from").getString(0));
        assertEquals("recipient@example.com", message.getJsonArray("to").getString(0));
    }

    // What a test or a developer wants is almost always the message that was just sent
    @Test
    void putsTheNewestFirst() throws IOException
    {
        this.caught("older", "Older", 10);
        this.caught("newest", "Newest", 0);
        this.caught("middle", "Middle", 5);

        final JsonArray messages = this.read().getJsonArray("messages");

        assertEquals(3, messages.size());
        assertEquals("Newest", messages.getJsonObject(0).getString("subject"));
        assertEquals("Middle", messages.getJsonObject(1).getString("subject"));
        assertEquals("Older", messages.getJsonObject(2).getString("subject"));
    }

    // Sorting must not throw on a message that somehow says nothing about when it arrived; it goes last
    @Test
    void toleratesAMessageWithNoDate() throws IOException
    {
        this.caught("dated", "Dated", 5);
        this.context.create().resource(HOME + "/undated", Map.of(
            "jcr:primaryType", CaughtMailService.MESSAGE_TYPE, "subject", "Undated"));

        final JsonArray messages = this.read().getJsonArray("messages");

        assertEquals("Dated", messages.getJsonObject(0).getString("subject"));
        assertEquals("Undated", messages.getJsonObject(1).getString("subject"));
        assertFalse(messages.getJsonObject(1).containsKey("caughtAt"));
    }

    // The one date format the platform writes, so a caller parses this the same way it parses everything else
    @Test
    void writesTheDateTheWayEverythingElseDoes() throws IOException
    {
        this.caught("one", "A subject", 0);

        final String when = this.read().getJsonArray("messages").getJsonObject(0).getString("caughtAt");

        assertEquals(when, DateUtils.toString(this.context.resourceResolver().getResource(HOME + "/one")
            .getValueMap().get("caughtAt", Calendar.class)));
    }

    // Every list is stated, empty or not, so a caller can tell "nobody was copied" from "this reader has to
    // guess" without checking for a missing key
    @Test
    void statesEveryListEvenWhenEmpty() throws IOException
    {
        this.caught("one", "A subject", 0);

        final JsonObject message = this.read().getJsonArray("messages").getJsonObject(0);

        assertTrue(message.getJsonArray("cc").isEmpty());
        assertTrue(message.getJsonArray("bcc").isEmpty());
        assertTrue(message.getJsonArray("replyTo").isEmpty());
        assertTrue(message.getJsonArray("headers").isEmpty());
    }

    @Test
    void answersAsJson() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource(HOME));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doGet(request, response);

        assertTrue(response.getContentType().startsWith("application/json"));
    }
}
