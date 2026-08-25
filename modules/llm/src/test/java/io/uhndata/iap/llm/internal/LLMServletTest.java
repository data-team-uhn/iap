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
package io.uhndata.iap.llm.internal;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMClientFactory;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LLMServlet}, the chat endpoint: what it accepts as a request body, and how it reports
 * a refusal or an upstream failure.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LLMServletTest
{
    private static final String REPLY = "Hello there";

    private final SlingContext context = new SlingContext();

    private LLMServlet servlet;

    private RecordingClient client;

    private IOException factoryFailure;

    private MockSlingJakartaHttpServletResponse lastResponse;

    /**
     * Records what the servlet asked the model for, and can be told to fail.
     */
    private static final class RecordingClient implements LLMClient
    {
        private String systemPrompt;

        private List<LLMMessage> messages;

        private String singleMessage;

        private IOException failure;

        private String answer() throws IOException
        {
            if (this.failure != null) {
                throw this.failure;
            }
            return REPLY;
        }

        @Override
        public String chat(final String userMessage) throws IOException
        {
            this.singleMessage = userMessage;
            return answer();
        }

        @Override
        public String chat(final String system, final String userMessage) throws IOException
        {
            this.systemPrompt = system;
            this.singleMessage = userMessage;
            return answer();
        }

        @Override
        public String chat(final String system, final List<LLMMessage> conversation) throws IOException
        {
            this.systemPrompt = system;
            this.messages = conversation;
            return answer();
        }

        @Override
        public String chat(final String system, final List<LLMMessage> conversation,
            final LLMRequestOptions options) throws IOException
        {
            return chat(system, conversation);
        }
    }

    @BeforeEach
    void setUp() throws Exception
    {
        this.servlet = new LLMServlet();
        this.client = new RecordingClient();

        final LLMClientFactory factory = new LLMClientFactory()
        {
            @Override
            public LLMClient getClient(final String providerApi)
            {
                return LLMServletTest.this.client;
            }

            @Override
            public LLMClient getActiveClient() throws IOException
            {
                if (LLMServletTest.this.factoryFailure != null) {
                    throw LLMServletTest.this.factoryFailure;
                }
                return LLMServletTest.this.client;
            }
        };
        final Field field = LLMServlet.class.getDeclaredField("llmClientFactory");
        field.setAccessible(true);
        field.set(this.servlet, factory);
    }

    private MockSlingJakartaHttpServletResponse post(final String body) throws IOException
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setMethod("POST");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        this.servlet.doPost(request, response);
        this.lastResponse = response;
        return response;
    }

    private JsonObject responseBody() throws IOException
    {
        try (JsonReader reader = Json.createReader(new StringReader(this.lastResponse.getOutputAsString()))) {
            return reader.readObject();
        }
    }

    @Test
    void answersASingleQuestion() throws IOException
    {
        post("{\"message\":\"Hello\"}");

        assertEquals(REPLY, responseBody().getString("response"));
        assertEquals("Hello", this.client.singleMessage);
        assertNull(this.client.systemPrompt, "no system prompt was sent");
    }

    @Test
    void passesOnASystemPrompt() throws IOException
    {
        post("{\"message\":\"Hello\",\"system\":\"Be brief\"}");

        assertEquals(REPLY, responseBody().getString("response"));
        assertEquals("Be brief", this.client.systemPrompt);
        assertEquals("Hello", this.client.singleMessage);
    }

    @Test
    void answersAConversation() throws IOException
    {
        post("{\"system\":\"Be brief\",\"messages\":["
            + "{\"role\":\"user\",\"content\":\"Hello\"},"
            + "{\"role\":\"assistant\",\"content\":\"Hi\"},"
            + "{\"role\":\"user\",\"content\":\"How are you?\"}]}");

        assertEquals(REPLY, responseBody().getString("response"));
        assertEquals("Be brief", this.client.systemPrompt);
        assertEquals(3, this.client.messages.size());
        assertEquals("assistant", this.client.messages.get(1).getRole());
        assertEquals("How are you?", this.client.messages.get(2).getContent());
    }

    @Test
    void refusesARequestThatIsNotJson() throws IOException
    {
        post("not json at all");

        assertEquals(400, this.lastResponse.getStatus());
        assertTrue(responseBody().getString("error").contains("Invalid JSON"));
    }

    @Test
    void refusesARequestWithNothingToSay() throws IOException
    {
        post("{\"system\":\"Be brief\"}");

        assertEquals(400, this.lastResponse.getStatus());
        assertTrue(responseBody().getString("error").contains("message"));
    }

    @Test
    void refusesABlankMessage() throws IOException
    {
        post("{\"message\":\"   \"}");

        assertEquals(400, this.lastResponse.getStatus());
        assertTrue(responseBody().getString("error").contains("message"));
    }

    @Test
    void reportsAnUpstreamFailureAsABadGateway() throws IOException
    {
        this.client.failure = new IOException("the model is unreachable");

        post("{\"message\":\"Hello\"}");

        assertEquals(502, this.lastResponse.getStatus());
        assertEquals("The LLM request could not be completed", responseBody().getString("error"));
        assertFalse(responseBody().getString("error").contains("unreachable"),
            "the client's own account of the failure stays in the log");
    }

    @Test
    void reportsAnUnresolvableClientAsABadGateway() throws IOException
    {
        this.factoryFailure = new IOException("no client for the active provider");

        post("{\"message\":\"Hello\"}");

        assertEquals(502, this.lastResponse.getStatus());
        assertEquals("The LLM request could not be completed", responseBody().getString("error"));
    }
}
