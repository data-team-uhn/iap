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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.llm.LLMSettings.ModelSettings;
import io.uhndata.iap.llm.LLMSettings.ProviderSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OpenAIClient}, driven against a local stand-in for an OpenAI-compatible endpoint so
 * that the request the client actually builds can be inspected.
 *
 * @version $Id$
 * @since 0.1.0
 */
class OpenAIClientTest
{
    private static final String MODEL = "llama3.2-3b";

    private static final String REPLY = "Hello there";

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},"
        + "\"required\":[\"ok\"],\"count\":3,\"ratio\":1.5,\"nested\":[1,\"two\",true,false,null]}";

    private static final String HELLO = "Hello";

    private static final String BE_BRIEF = "Be brief";

    private final AtomicReference<JsonObject> lastRequest = new AtomicReference<>();

    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    private final AtomicInteger requests = new AtomicInteger();

    private HttpServer server;

    private int status = 200;

    /**
     * An OpenAI client wired to the stand-in endpoint, with the environment under the test's control.
     */
    private static final class TestClient extends OpenAIClient
    {
        private final Map<String, String> variables;

        TestClient(final LLMSettings settings, final Map<String, String> variables)
        {
            this(settings, variables, CallGateImplTest.gate(4));
        }

        TestClient(final LLMSettings settings, final Map<String, String> variables, final CallGateImpl gate)
        {
            this.variables = variables;
            setConfigurationService(() -> settings);
            bindCallGate(gate);
        }

        @Override
        protected String environment(final String name)
        {
            return this.variables.get(name);
        }
    }

    @BeforeEach
    void startServer() throws IOException
    {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/chat/completions", this::handle);
        this.server.start();
    }

    @AfterEach
    void stopServer()
    {
        this.server.stop(0);
    }

    private void handle(final HttpExchange exchange) throws IOException
    {
        this.requests.incrementAndGet();
        try (InputStream in = exchange.getRequestBody(); JsonReader reader = Json.createReader(in)) {
            this.lastRequest.set(reader.readObject());
        }
        this.lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        final String body;
        if (this.status == 200) {
            body = Json.createObjectBuilder()
                .add("choices", Json.createArrayBuilder()
                    .add(Json.createObjectBuilder()
                        .add("message", Json.createObjectBuilder().add("role", "assistant").add("content", REPLY))
                        .add("finish_reason", "stop")))
                .build().toString();
        } else {
            body = Json.createObjectBuilder()
                .add("error", Json.createObjectBuilder().add("message", "upstream is unwell"))
                .build().toString();
        }
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(this.status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String endpoint()
    {
        return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1";
    }

    private LLMSettings settings(final Map<String, Object> providerExtras)
    {
        final Map<String, Object> extra = new HashMap<>(providerExtras);
        final String apiKeyEnvVar = (String) extra.remove("apiKeyEnvVar");
        final ProviderSettings provider = new ProviderSettings(endpoint(), apiKeyEnvVar, 10, extra);
        final ModelSettings model = new ModelSettings(0, 100, 0.25, 0, 0, null, Map.of());
        return new LLMSettings("local", provider, MODEL, model);
    }

    private TestClient client()
    {
        return new TestClient(settings(Map.of()), Map.of());
    }

    private JsonObject request()
    {
        return this.lastRequest.get();
    }

    @Test
    void sendsTheConversationAndReturnsTheReply() throws IOException
    {
        assertEquals(REPLY, client().chat(BE_BRIEF, HELLO));

        final JsonObject sent = request();
        assertEquals(MODEL, sent.getString("model"));
        assertEquals(0.25d, sent.getJsonNumber("temperature").doubleValue());
        assertEquals(100, sent.getJsonNumber("max_tokens").intValue());
        assertEquals("system", sent.getJsonArray("messages").getJsonObject(0).getString("role"));
        assertEquals(BE_BRIEF, sent.getJsonArray("messages").getJsonObject(0).getString("content"));
        assertEquals("user", sent.getJsonArray("messages").getJsonObject(1).getString("role"));
        assertEquals(HELLO, sent.getJsonArray("messages").getJsonObject(1).getString("content"));
    }

    @Test
    void mapsEveryConversationRole() throws IOException
    {
        client().chat(null, List.of(
            new LLMMessage("user", HELLO),
            new LLMMessage("assistant", "Hi"),
            new LLMMessage("system", "Stay on topic"),
            new LLMMessage("unknown", "Treated as a user turn")));

        final List<String> roles = request().getJsonArray("messages").getValuesAs(JsonObject.class)
            .stream().map(message -> message.getString("role")).toList();
        assertEquals(List.of("user", "assistant", "system", "user"), roles);
    }

    @Test
    void alwaysTurnsOffTheThinkingTemplate() throws IOException
    {
        client().chat(HELLO);

        assertFalse(request().getJsonObject("chat_template_kwargs").getBoolean("enable_thinking"));
        assertFalse(request().containsKey("project_id"), "no project is configured");
        assertFalse(request().containsKey("response_format"), "no schema was requested");
    }

    @Test
    void sendsTheProjectIdWhenTheProviderScopesCallsToAProject() throws IOException
    {
        new TestClient(settings(Map.of("projectId", "some-project")), Map.of()).chat(HELLO);

        assertEquals("some-project", request().getString("project_id"));
    }

    @Test
    void pinsTheReplyToASchemaWhenOneIsRequested() throws IOException
    {
        client().chat(BE_BRIEF, List.of(new LLMMessage("user", HELLO)),
            LLMRequestOptions.builder().jsonSchema("document_summary", SCHEMA).build());

        final JsonObject format = request().getJsonObject("response_format");
        assertEquals("json_schema", format.getString("type"));
        final JsonObject jsonSchema = format.getJsonObject("json_schema");
        assertEquals("document_summary", jsonSchema.getString("name"));
        assertTrue(jsonSchema.getBoolean("strict"));

        // the schema has to arrive as real nested JSON, not as an escaped string
        final JsonObject schema = jsonSchema.getJsonObject("schema");
        assertEquals("object", schema.getString("type"));
        assertEquals("boolean", schema.getJsonObject("properties").getJsonObject("ok").getString("type"));
        assertEquals("ok", schema.getJsonArray("required").getString(0));
        assertEquals(3, schema.getJsonNumber("count").intValue());
        assertEquals(1.5d, schema.getJsonNumber("ratio").doubleValue());
        assertEquals(5, schema.getJsonArray("nested").size(), "every element type survives the conversion");
    }

    @Test
    void raisesTheOutputCeilingForOneCall() throws IOException
    {
        client().chat(BE_BRIEF, List.of(new LLMMessage("user", HELLO)),
            LLMRequestOptions.withMaxOutputTokens(4096));

        assertEquals(4096, request().getJsonNumber("max_tokens").intValue());
    }

    @Test
    void sendsTheApiKeyFromTheConfiguredEnvironmentVariable() throws IOException
    {
        new TestClient(settings(Map.of("apiKeyEnvVar", "TEST_LLM_KEY")),
            Map.of("TEST_LLM_KEY", "s3cret")).chat(HELLO);

        assertEquals("Bearer s3cret", this.lastAuthorization.get());
    }

    @Test
    void sendsNoApiKeyWhenTheVariableIsNamedButUnset() throws IOException
    {
        new TestClient(settings(Map.of("apiKeyEnvVar", "TEST_LLM_KEY")), Map.of()).chat(HELLO);

        assertNull(this.lastAuthorization.get());
    }

    @Test
    void sendsNoApiKeyWhenTheProviderNamesNoVariable() throws IOException
    {
        client().chat(HELLO);

        assertNull(this.lastAuthorization.get());
    }

    @Test
    void acceptsAnEndpointThatAlreadyNamesTheChatCompletionsPath() throws IOException
    {
        final ProviderSettings provider =
            new ProviderSettings(endpoint() + "/chat/completions/", null, 10, null);
        final ModelSettings model = new ModelSettings(0, 100, 0.0, 0, 0, null, null);
        final LLMSettings settings = new LLMSettings("local", provider, MODEL, model);

        assertEquals(REPLY, new TestClient(settings, Map.of()).chat(HELLO));
    }

    @Test
    void bindsTheConfigurationServiceTheComponentIsGiven() throws IOException
    {
        final LLMSettings settings = settings(Map.of());
        final OpenAIClient client = new OpenAIClient();
        client.bindConfigurationService(() -> settings);
        client.bindCallGate(CallGateImplTest.gate(1));

        assertEquals(REPLY, client.chat(HELLO), "the bound configuration is what the call uses");
    }

    @Test
    void readsRealEnvironmentVariables()
    {
        assertNull(new OpenAIClient().environment("IAP_LLM_A_VARIABLE_THAT_IS_NOT_SET"));
    }

    @Test
    void reportsAnUpstreamFailureAsAnIOException()
    {
        this.status = 500;

        final IOException failure = assertThrows(IOException.class, () -> client().chat(HELLO));
        assertTrue(failure.getMessage().startsWith("OpenAI-compatible LLM request failed"));
    }

    @Test
    void reportsAConfigurationFailureUnchanged()
    {
        final OpenAIClient client = new OpenAIClient();
        client.bindConfigurationService(() -> {
            throw new IOException("no active provider");
        });

        final IOException failure = assertThrows(IOException.class, () -> client.chat(HELLO));
        assertEquals("no active provider", failure.getMessage());
    }

    @Test
    void reportsAnInvalidResponseSchemaAsAnIOException()
    {
        final IOException failure = assertThrows(IOException.class,
            () -> client().chat(BE_BRIEF, List.of(new LLMMessage("user", HELLO)),
                LLMRequestOptions.builder().jsonSchema("bad-schema", "not json").build()));

        assertTrue(failure.getMessage().startsWith("OpenAI-compatible LLM request failed"));
    }

    @Test
    void reusesTheModelForRepeatedCallsWithUnchangedSettings() throws IOException
    {
        final TestClient reused = client();

        assertEquals(REPLY, reused.chat(HELLO));
        assertEquals(REPLY, reused.chat(HELLO));
    }

    @Test
    void sendsAFailedRequestAgainOnceOnly()
    {
        this.status = 500;

        assertThrows(IOException.class, () -> client().chat(HELLO));

        assertEquals(2, this.requests.get(), "the first try and one retry");
    }

    // The old cache held one model. Two option sets taking turns rebuilt it on every call.
    @Test
    void keepsAModelPerOptionSetRatherThanOneSlot() throws IOException
    {
        final TestClient reused = client();
        final LLMRequestOptions bigger = LLMRequestOptions.withMaxOutputTokens(4096);

        reused.chat(HELLO);
        reused.chat(BE_BRIEF, List.of(new LLMMessage("user", HELLO)), bigger);
        reused.chat(HELLO);
        reused.chat(BE_BRIEF, List.of(new LLMMessage("user", HELLO)), bigger);

        assertEquals(2, reused.countModels(), "one per option set, neither rebuilt");
    }

    @Test
    void sharesOneHttpClientAcrossTheModelsOfOneProvider() throws IOException
    {
        final TestClient reused = client();

        reused.chat(HELLO);
        reused.chat(BE_BRIEF, List.of(new LLMMessage("user", HELLO)), LLMRequestOptions.withMaxOutputTokens(4096));

        assertEquals(2, reused.countModels());
        assertEquals(1, reused.countHttpClients());
    }

    @Test
    void givesItsCallSlotBackAfterEveryCall() throws IOException
    {
        final TestClient client = new TestClient(settings(Map.of()), Map.of(), CallGateImplTest.gate(1));

        assertEquals(REPLY, client.chat(HELLO));
        assertEquals(REPLY, client.chat(HELLO), "the single slot is free again");
    }

    @Test
    void givesItsCallSlotBackWhenTheCallFails() throws IOException
    {
        final TestClient client = new TestClient(settings(Map.of()), Map.of(), CallGateImplTest.gate(1));
        this.status = 500;
        assertThrows(IOException.class, () -> client.chat(HELLO));

        this.status = 200;

        assertEquals(REPLY, client.chat(HELLO));
    }

    // Waiting longer than the read timeout means the provider is already behind, so the call fails instead
    @Test
    void failsWithoutCallingWhenNoSlotComesFreeInTime() throws IOException
    {
        final CallGateImpl gate = CallGateImplTest.gate(1);
        gate.acquire(Duration.ZERO);
        final ProviderSettings provider = new ProviderSettings(endpoint(), null, 1, Map.of());
        final LLMSettings settings = new LLMSettings("local", provider, MODEL,
            new ModelSettings(0, 100, 0.25, 0, 0, null, Map.of()));
        final TestClient client = new TestClient(settings, Map.of(), gate);

        final IOException failure = assertThrows(IOException.class, () -> client.chat(HELLO));

        assertTrue(failure.getMessage().contains("call slot"), failure.getMessage());
        assertEquals(0, this.requests.get(), "nothing was sent");
    }
}
