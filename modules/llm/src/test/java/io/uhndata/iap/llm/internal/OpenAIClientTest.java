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
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.llm.LLMSettings.ModelSettings;
import io.uhndata.iap.llm.LLMSettings.ProviderSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    private boolean emptyContent;

    /**
     * An OpenAI client wired to the stand-in endpoint, with the environment under the test's control.
     */
    private static final class TestClient extends OpenAIClient
    {
        private final Map<String, String> variables;

        TestClient(final LLMSettings settings, final Map<String, String> variables)
        {
            this(settings, variables, CallGateImplTest.gate());
        }

        TestClient(final LLMSettings settings, final Map<String, String> variables, final CallGateImpl gate)
        {
            this.variables = variables;
            bindConfigurationService(() -> settings);
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
                        .add("message", Json.createObjectBuilder().add("role", "assistant")
                            .add("content", this.emptyContent ? "" : REPLY))
                        .add("finish_reason", this.emptyContent ? "length" : "stop")))
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
        final ModelSettings model = new ModelSettings(0, 0.25, null, Map.of());
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

    /** One "Hello" turn, no system prompt and no per-call options, which is what most tests send. */
    private static String say(final OpenAIClient client) throws IOException
    {
        return client.chat(null, List.of(new LLMMessage("user", HELLO)), null);
    }

    @Test
    void sendsTheConversationAndReturnsTheReply() throws IOException
    {
        assertEquals(REPLY, client().chat(BE_BRIEF, List.of(new LLMMessage("user", HELLO)), null));

        final JsonObject sent = request();
        assertEquals(MODEL, sent.getString("model"));
        assertEquals(0.25d, sent.getJsonNumber("temperature").doubleValue());
        assertFalse(sent.containsKey("max_tokens"), "a call that asks for no ceiling leaves it to the provider");
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
            new LLMMessage("unknown", "Treated as a user turn")), null);

        final List<String> roles = request().getJsonArray("messages").getValuesAs(JsonObject.class)
            .stream().map(message -> message.getString("role")).toList();
        assertEquals(List.of("user", "assistant", "system", "user"), roles);
    }

    @Test
    void sendsNoThinkingTemplateUnlessTheProviderAsksForOne() throws IOException
    {
        say(client());

        assertFalse(request().containsKey("chat_template_kwargs"), "no provider asked for it");
        assertFalse(request().containsKey("project_id"), "no project is configured");
        assertFalse(request().containsKey("response_format"), "no schema was requested");
    }

    @Test
    void turnsOffTheThinkingTemplateForAProviderThatAsks() throws IOException
    {
        say(new TestClient(settings(Map.of("disableThinking", Boolean.TRUE)), Map.of()));

        assertFalse(request().getJsonObject("chat_template_kwargs").getBoolean("enable_thinking"));
    }

    @Test
    void sendsTheProjectIdWhenTheProviderScopesCallsToAProject() throws IOException
    {
        say(new TestClient(settings(Map.of("projectId", "some-project")), Map.of()));

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
            LLMRequestOptions.builder().maxOutputTokens(4096).build());

        assertEquals(4096, request().getJsonNumber("max_tokens").intValue());
    }

    @Test
    void sendsTheApiKeyFromTheConfiguredEnvironmentVariable() throws IOException
    {
        say(new TestClient(settings(Map.of("apiKeyEnvVar", "TEST_LLM_KEY")),
            Map.of("TEST_LLM_KEY", "s3cret")));

        assertEquals("Bearer s3cret", this.lastAuthorization.get());
    }

    @Test
    void refusesWhenTheNamedApiKeyVariableIsUnset()
    {
        final IOException failure = assertThrows(IOException.class,
            () -> say(new TestClient(settings(Map.of("apiKeyEnvVar", "TEST_LLM_KEY")), Map.of())));

        assertTrue(failure.getMessage().contains("TEST_LLM_KEY"));
        assertNull(this.lastAuthorization.get(), "nothing reached the provider");
    }

    @Test
    void sendsNoApiKeyWhenTheProviderNamesNoVariable() throws IOException
    {
        say(client());

        assertNull(this.lastAuthorization.get());
    }

    @Test
    void acceptsAnEndpointThatAlreadyNamesTheChatCompletionsPath() throws IOException
    {
        final ProviderSettings provider =
            new ProviderSettings(endpoint() + "/chat/completions/", null, 10, null);
        final ModelSettings model = new ModelSettings(0, 0.0, null, null);
        final LLMSettings settings = new LLMSettings("local", provider, MODEL, model);

        assertEquals(REPLY, say(new TestClient(settings, Map.of())));
    }

    @Test
    void usesTheConfigurationServiceItWasGiven() throws IOException
    {
        final LLMSettings settings = settings(Map.of());
        final OpenAIClient client = new TestClient(settings, Map.of());

        assertEquals(REPLY, say(client), "the bound configuration is what the call uses");
    }

    @Test
    void bindConfigurationServiceWiresTheParentField() throws IOException
    {
        final LLMSettings settings = settings(Map.of());
        final OpenAIClient client = new OpenAIClient();
        client.bindCallGate(CallGateImplTest.gate());
        client.bindConfigurationService(() -> settings);

        assertEquals(REPLY, say(client), "the DS bind method is what fills the parent field");
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

        final IOException failure = assertThrows(IOException.class, () -> say(client()));
        assertTrue(failure.getMessage().startsWith("OpenAI-compatible LLM request failed"));
    }

    @Test
    void reportsAConfigurationFailureUnchanged()
    {
        final OpenAIClient client = new OpenAIClient()
        {
            @Override
            protected LLMConfigurationService getConfigurationService()
            {
                return () -> {
                    throw new IOException("no active provider");
                };
            }
        };

        final IOException failure = assertThrows(IOException.class, () -> say(client));
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

        assertEquals(REPLY, say(reused));
        assertEquals(REPLY, say(reused));
    }

    @Test
    void sendsTheModelIdentifierTheModelDeclares() throws IOException
    {
        say(new TestClient(settingsWithModel(Map.of("modelId", "llama3.2:3b")), Map.of()));

        assertEquals("llama3.2:3b", request().getString("model"),
            "a JCR name cannot hold the colon, so the node name is not the identifier");
    }

    @Test
    void clampsAnOversizeTokenCeilingRatherThanTruncatingIt() throws IOException
    {
        client().chat(null, List.of(new LLMMessage("user", HELLO)),
            LLMRequestOptions.builder().maxOutputTokens(5_000_000_000L).build());

        assertEquals(Integer.MAX_VALUE, request().getJsonNumber("max_tokens").intValue());
    }

    @Test
    void refusesAnAnswerWithNoContent()
    {
        this.emptyContent = true;

        final IOException failure = assertThrows(IOException.class, () -> say(client()));

        assertTrue(failure.getMessage().contains("empty answer"));
    }

    @Test
    void reusesTheModelWhenOnlyTheCallOptionsChange() throws Exception
    {
        final TestClient client = client();
        say(client);
        final Object first = cachedModel(client);
        client.chat(null, List.of(new LLMMessage("user", HELLO)),
            LLMRequestOptions.builder().maxOutputTokens(7).build());

        assertSame(first, cachedModel(client), "the options travel on the request, not the model");
        assertEquals(7, request().getJsonNumber("max_tokens").intValue(), "and they still reach the wire");
    }

    private static Object cachedModel(final OpenAIClient client) throws Exception
    {
        final Field field = OpenAIClient.class.getDeclaredField("cachedModel");
        field.setAccessible(true);
        return field.get(client);
    }

    private LLMSettings settingsWithModel(final Map<String, Object> modelExtra)
    {
        final ProviderSettings provider = new ProviderSettings(endpoint(), null, 10, Map.of());
        final ModelSettings model = new ModelSettings(0, 0.25, null, modelExtra);
        return new LLMSettings("local", provider, MODEL, model);
    }
}
