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
package io.uhndata.iap.slacknotifications.internal;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.httprequests.api.HttpRequests;
import io.uhndata.iap.httprequests.api.HttpResponse;
import io.uhndata.iap.slacknotifications.spi.SlackNotificationProducer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SlackNotificationsTask}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class SlackNotificationsTaskTest
{
    /** Records what was posted, and answers whatever the test asked it to. */
    private static final class Webhook implements HttpRequests
    {
        private final List<String> posted = new ArrayList<>();

        private HttpResponse answer = new HttpResponse(200, "ok");

        private IOException failure;

        @Override
        public HttpResponse post(final String url, final String body, final String contentType) throws IOException
        {
            if (this.failure != null) {
                throw this.failure;
            }
            this.posted.add(body);
            return this.answer;
        }

        @Override
        public HttpResponse post(final String url, final String body, final String contentType, final Charset charset)
            throws IOException
        {
            return post(url, body, contentType);
        }
    }

    /** A producer saying exactly what the test told it to say. */
    private static final class Producer implements SlackNotificationProducer
    {
        private final String name;

        private final List<JsonObject> messages;

        Producer(final String name, final List<JsonObject> messages)
        {
            this.name = name;
            this.messages = messages;
        }

        @Override
        public String getName()
        {
            return this.name;
        }

        @Override
        public List<JsonObject> prepareMessages(final Map<String, String> extraParameters)
        {
            return this.messages;
        }
    }

    private final Webhook webhook = new Webhook();

    @Test
    void postsWhatTheProducersPrepared()
    {
        task(List.of(producer("first", "one"), producer("second", "two")), List.of(), "Nightly", true).run();

        assertEquals(1, this.webhook.posted.size());
        final JsonObject posted = parse(this.webhook.posted.get(0));
        assertEquals("Nightly", posted.getString("text"));
        assertEquals(2, posted.getJsonArray("attachments").size());
        assertEquals("one", posted.getJsonArray("attachments").getJsonObject(0).getString("title"));
    }

    @Test
    void onlyTheIncludedProducersAreAsked()
    {
        task(List.of(producer("first", "one"), producer("second", "two")), List.of("second"), "", true).run();

        final JsonObject posted = parse(this.webhook.posted.get(0));
        assertEquals(1, posted.getJsonArray("attachments").size());
        assertEquals("two", posted.getJsonArray("attachments").getJsonObject(0).getString("title"));
        // No title was configured, so none is sent
        assertFalse(posted.containsKey("text"));
    }

    @Test
    void producersWithNothingToSayDoNotMakeAMessage()
    {
        // Every producer answers with an empty list rather than with nothing, which must still count as silence
        task(List.of(new Producer("quiet", List.of())), List.of(), "Nightly", true).run();

        assertTrue(this.webhook.posted.isEmpty());
    }

    @Test
    void aProducerAnsweringWithNothingAtAllIsTolerated()
    {
        task(List.of(new Producer("broken", null), producer("second", "two")), List.of(), "", true).run();

        assertEquals(1, parse(this.webhook.posted.get(0)).getJsonArray("attachments").size());
    }

    @Test
    void sayingNothingIsAnOptionWhenAskedToReportAnyway()
    {
        task(List.of(new Producer("quiet", List.of())), List.of(), "", false).run();

        final JsonObject attachment = parse(this.webhook.posted.get(0)).getJsonArray("attachments").getJsonObject(0);
        assertEquals("All is good", attachment.getString("title"));
        assertEquals("Nothing to report", attachment.getString("text"));
    }

    @Test
    void aRefusedMessageIsNotMistakenForADeliveredOne()
    {
        this.webhook.answer = new HttpResponse(400, "invalid_payload");

        // Nothing throws, but the refusal must be noticed rather than treated as success
        task(List.of(producer("first", "one")), List.of(), "", true).run();

        assertEquals(1, this.webhook.posted.size());
    }

    @Test
    void anUnreachableWebhookDoesNotBreakTheJob()
    {
        this.webhook.failure = new IOException("no route to host");

        task(List.of(producer("first", "one")), List.of(), "", true).run();

        assertTrue(this.webhook.posted.isEmpty());
    }

    @Test
    void eachRunAsksTheProducersRegisteredByThen()
    {
        // The list the task is given is the live one Declarative Services keeps up to date, so a task scheduled once
        // must follow it rather than freeze the producers that happened to exist at scheduling time
        final List<SlackNotificationProducer> registered = new CopyOnWriteArrayList<>();
        registered.add(producer("first", "one"));
        final SlackNotificationsTask task = task(registered, List.of(), "", true);

        task.run();
        assertEquals(1, parse(this.webhook.posted.get(0)).getJsonArray("attachments").size());

        // A producer bundle stops and another starts between the two runs
        registered.remove(0);
        registered.add(producer("second", "two"));
        task.run();

        final JsonObject second = parse(this.webhook.posted.get(1));
        assertEquals(1, second.getJsonArray("attachments").size());
        assertEquals("two", second.getJsonArray("attachments").getJsonObject(0).getString("title"));
    }

    private SlackNotificationsTask task(final List<SlackNotificationProducer> producers, final List<String> include,
        final String title, final boolean skipEmpty)
    {
        return new SlackNotificationsTask(this.webhook, producers, "https://example.invalid/hook", title, include,
            Map.of(), skipEmpty);
    }

    private SlackNotificationProducer producer(final String name, final String title)
    {
        return new Producer(name, List.of(Json.createObjectBuilder().add("title", title).build()));
    }

    private JsonObject parse(final String json)
    {
        return Json.createReader(new java.io.StringReader(json)).readObject();
    }
}
