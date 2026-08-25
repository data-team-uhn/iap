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
import java.util.HashMap;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
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
 * Unit tests for {@link LLMConfigServlet}: serving the provider and model catalog, and switching the active
 * selection.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LLMConfigServletTest
{
    private static final String CONFIG_PATH = "/apps/iap/config/LLM";

    private static final String ACTIVE_PROVIDER = "activeProvider";

    private static final String ACTIVE_MODEL = "activeModel";

    private static final String PROVIDER = "local";

    private static final String MODEL = "llama3.2-3b";

    private final SlingContext context = new SlingContext();

    private LLMConfigServlet servlet;

    @BeforeEach
    void setUp()
    {
        this.servlet = new LLMConfigServlet();

        final Map<String, Object> config = new HashMap<>();
        config.put("jcr:primaryType", "nt:unstructured");
        config.put("title", "LLM Configuration");
        config.put(ACTIVE_PROVIDER, PROVIDER);
        config.put(ACTIVE_MODEL, MODEL);
        this.context.create().resource(CONFIG_PATH, config);

        this.context.create().resource(CONFIG_PATH + "/" + PROVIDER, Map.of(
            "sling:resourceType", "llm/Provider",
            "label", "Local (Ollama)",
            "api", "openai",
            "endpoint", "http://localhost:11434/v1",
            "timeoutSeconds", 600L));
        this.context.create().resource(CONFIG_PATH + "/" + PROVIDER + "/" + MODEL, Map.of(
            "maxOutputTokens", 1024L,
            "temperature", 0.25d,
            "chunked", Boolean.TRUE,
            "developer", "meta"));
        this.context.create().resource(CONFIG_PATH + "/" + PROVIDER + "/other-model", Map.of(
            "maxOutputTokens", 2048L));
    }

    private MockSlingJakartaHttpServletRequest request(final Resource resource)
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(resource);
        return request;
    }

    private MockSlingJakartaHttpServletRequest requestConfig()
    {
        return request(this.context.resourceResolver().getResource(CONFIG_PATH));
    }

    private JsonObject responseBody(final MockSlingJakartaHttpServletResponse response) throws IOException
    {
        try (JsonReader reader = Json.createReader(new StringReader(response.getOutputAsString()))) {
            return reader.readObject();
        }
    }

    @Test
    void servesTheCatalogAndTheActiveSelection() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doGet(requestConfig(), response);

        final JsonObject body = responseBody(response);
        assertEquals(PROVIDER, body.getString(ACTIVE_PROVIDER));
        assertEquals(MODEL, body.getString(ACTIVE_MODEL));

        final JsonObject provider = body.getJsonArray("providers").getJsonObject(0);
        assertEquals(PROVIDER, provider.getString("name"));
        assertEquals("Local (Ollama)", provider.getString("label"));
        assertEquals(600, provider.getJsonNumber("timeoutSeconds").longValue());
        assertEquals(2, provider.getJsonArray("models").size());

        final JsonObject model = provider.getJsonArray("models").getJsonObject(0);
        assertEquals(MODEL, model.getString("name"));
        assertEquals(1024, model.getJsonNumber("maxOutputTokens").longValue());
        assertEquals(0.25d, model.getJsonNumber("temperature").doubleValue());
        assertTrue(model.getBoolean("chunked"));
        assertEquals("meta", model.getString("developer"));
    }

    @Test
    void leavesTheJcrAndSlingBookkeepingOutOfTheCatalog() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doGet(requestConfig(), response);

        final JsonObject provider = responseBody(response).getJsonArray("providers").getJsonObject(0);
        assertFalse(provider.containsKey("sling:resourceType"));
        assertFalse(provider.containsKey("jcr:primaryType"));
    }

    @Test
    void omitsAnActiveSelectionThatIsNotSet() throws IOException
    {
        this.context.create().resource("/apps/iap/config/Empty", Map.of("jcr:primaryType", "nt:unstructured"));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doGet(request(this.context.resourceResolver().getResource("/apps/iap/config/Empty")),
            response);

        final JsonObject body = responseBody(response);
        assertFalse(body.containsKey(ACTIVE_PROVIDER));
        assertFalse(body.containsKey(ACTIVE_MODEL));
        assertTrue(body.getJsonArray("providers").isEmpty());
    }

    @Test
    void switchesTheActiveSelection() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request = requestConfig();
        request.setParameterMap(Map.of(
            ACTIVE_PROVIDER, PROVIDER,
            ACTIVE_MODEL, "other-model"));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request, response);

        assertEquals("other-model", responseBody(response).getString(ACTIVE_MODEL));
        assertEquals("other-model", this.context.resourceResolver().getResource(CONFIG_PATH)
            .getValueMap().get(ACTIVE_MODEL, String.class));
    }

    @Test
    void refusesAnIncompleteSelection() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request = requestConfig();
        request.setParameterMap(Map.of(ACTIVE_PROVIDER, PROVIDER));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(responseBody(response).getString("error").contains("required"));
    }

    @Test
    void refusesAProviderOrModelThatIsNotInTheCatalog() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request = requestConfig();
        request.setParameterMap(Map.of(
            ACTIVE_PROVIDER, "anthropic",
            ACTIVE_MODEL, MODEL));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(responseBody(response).getString("error").contains("provider"));
        assertFalse(responseBody(response).getString("error").contains("anthropic"),
            "what was asked for is not echoed back");
    }

    @Test
    void refusesAModelTheProviderDoesNotOffer() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request = requestConfig();
        request.setParameterMap(Map.of(
            ACTIVE_PROVIDER, PROVIDER,
            ACTIVE_MODEL, "absent"));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(responseBody(response).getString("error").contains("model"));
        assertFalse(responseBody(response).getString("error").contains("absent"),
            "what was asked for is not echoed back");
    }

    @Test
    void refusesToWriteForSomeoneWithoutPermission() throws IOException
    {
        final Resource real = this.context.resourceResolver().getResource(CONFIG_PATH);
        final Resource readOnly = Mockito.spy(real);
        Mockito.doReturn(null).when(readOnly).adaptTo(ModifiableValueMap.class);
        final MockSlingJakartaHttpServletRequest request = request(readOnly);
        request.setParameterMap(Map.of(
            ACTIVE_PROVIDER, PROVIDER,
            ACTIVE_MODEL, "other-model"));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request, response);

        assertEquals(403, response.getStatus());
        assertTrue(responseBody(response).getString("error").contains("Not allowed"));
    }
}
