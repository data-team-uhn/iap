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
import java.math.BigDecimal;
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
    private static final String SELECTION_PATH = "/apps/iap/config/LLM";

    private static final String CATALOG_PATH = "/libs/iap/config/LLM";

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
        this.context.create().resource(SELECTION_PATH, config);

        this.context.create().resource(CATALOG_PATH, Map.of("jcr:primaryType", "nt:unstructured"));
        this.context.create().resource(CATALOG_PATH + "/" + PROVIDER, Map.of(
            "sling:resourceType", "llm/Provider",
            "label", "Local (Ollama)",
            "api", "openai",
            "endpoint", "http://localhost:11434/v1",
            "timeoutSeconds", 600L));
        this.context.create().resource(CATALOG_PATH + "/" + PROVIDER + "/" + MODEL, Map.of(
            "sling:resourceType", "llm/Model",
            "maxOutputTokens", 1024L,
            "temperature", 0.25d,
            "chunked", Boolean.TRUE,
            "developer", "meta"));
        this.context.create().resource(CATALOG_PATH + "/" + PROVIDER + "/other-model", Map.of(
            "sling:resourceType", "llm/Model",
            "maxOutputTokens", 2048L,
            "chunked", Boolean.FALSE));
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
        return request(this.context.resourceResolver().getResource(SELECTION_PATH));
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

        final JsonObject otherModel = provider.getJsonArray("models").getJsonObject(1);
        assertEquals("other-model", otherModel.getString("name"));
        assertFalse(otherModel.getBoolean("chunked"));
    }

    @Test
    void rendersMultivaluedAndDecimalPropertiesCorrectly() throws IOException
    {
        this.context.create().resource(CATALOG_PATH + "/" + PROVIDER + "/decimal-model", Map.of(
            "sling:resourceType", "llm/Model",
            "temperature", new BigDecimal("0.15"),
            "tags", new String[] { "fast", "cheap" }));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doGet(requestConfig(), response);

        final JsonObject provider = responseBody(response).getJsonArray("providers").getJsonObject(0);
        JsonObject decimalModel = null;
        for (final JsonObject model : provider.getJsonArray("models").getValuesAs(JsonObject.class)) {
            if ("decimal-model".equals(model.getString("name"))) {
                decimalModel = model;
            }
        }
        assertEquals(new BigDecimal("0.15"), decimalModel.getJsonNumber("temperature").bigDecimalValue());
        assertEquals(2, decimalModel.getJsonArray("tags").size());
        assertEquals("fast", decimalModel.getJsonArray("tags").getString(0));
        assertEquals("cheap", decimalModel.getJsonArray("tags").getString(1));
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
        // The catalog is read from its own fixed location, independent of which resource answers the
        // request, so it is still there even though this resource carries no active selection
        assertFalse(body.getJsonArray("providers").isEmpty());
    }

    @Test
    void servesAnEmptyCatalogWhenTheCatalogNodeIsMissing() throws IOException
    {
        this.context.resourceResolver().delete(this.context.resourceResolver().getResource(CATALOG_PATH));
        this.context.resourceResolver().commit();
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doGet(requestConfig(), response);

        final JsonObject body = responseBody(response);
        assertEquals(PROVIDER, body.getString(ACTIVE_PROVIDER));
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
        assertEquals("other-model", this.context.resourceResolver().getResource(SELECTION_PATH)
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
    void refusesAProviderNameThatIsAPathRatherThanAName() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request = requestConfig();
        request.setParameterMap(Map.of(
            ACTIVE_PROVIDER, "../../evil/provider",
            ACTIVE_MODEL, MODEL));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(responseBody(response).getString("error").contains("provider"));
    }

    @Test
    void refusesAModelNameThatIsAPathRatherThanAName() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request = requestConfig();
        request.setParameterMap(Map.of(
            ACTIVE_PROVIDER, PROVIDER,
            ACTIVE_MODEL, "../other-model"));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(responseBody(response).getString("error").contains("model"));
    }

    @Test
    void refusesAProviderNameThatResolvesToSomethingOfTheWrongType() throws IOException
    {
        // Same name as a real node under the catalog root, but not itself a provider
        this.context.create().resource(CATALOG_PATH + "/not-a-provider", Map.of(
            "sling:resourceType", "nt:unstructured"));
        final MockSlingJakartaHttpServletRequest request = requestConfig();
        request.setParameterMap(Map.of(
            ACTIVE_PROVIDER, "not-a-provider",
            ACTIVE_MODEL, MODEL));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(responseBody(response).getString("error").contains("provider"));
    }

    @Test
    void refusesAModelNameThatResolvesToSomethingOfTheWrongType() throws IOException
    {
        this.context.create().resource(CATALOG_PATH + "/" + PROVIDER + "/not-a-model", Map.of(
            "sling:resourceType", "nt:unstructured"));
        final MockSlingJakartaHttpServletRequest request = requestConfig();
        request.setParameterMap(Map.of(
            ACTIVE_PROVIDER, PROVIDER,
            ACTIVE_MODEL, "not-a-model"));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(responseBody(response).getString("error").contains("model"));
    }

    @Test
    void refusesToWriteForSomeoneWithoutPermission() throws IOException
    {
        final Resource real = this.context.resourceResolver().getResource(SELECTION_PATH);
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
