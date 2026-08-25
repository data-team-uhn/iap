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
package io.uhndata.iap.llm;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link LLMSettings}, covering the typed getters, their defaults, and the coercion of
 * JCR property values that arrive as strings.
 *
 * @version $Id$
 * @since 0.1.0
 */
class LLMSettingsTest
{
    private static final String PROVIDER = "prompter";

    private static final String MODEL = "GPT-OSS-120B";

    @Test
    void keepsTheProviderAndModelNames()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER, Map.of(), MODEL, Map.of());
        assertEquals(PROVIDER, settings.getProviderName());
        assertEquals(MODEL, settings.getModelName());
    }

    @Test
    void nullPropertyMapsBecomeEmpty()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER, null, MODEL, null);
        assertNull(settings.getEndpoint());
        assertNull(settings.getDeveloper());
        assertEquals(120, settings.getTimeoutSeconds());
        assertEquals(1000, settings.getMaxOutputTokens());
    }

    @Test
    void readsTheProviderSettings()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER,
            Map.of("endpoint", "https://example.invalid/v1",
                "apiKeyEnvVar", "PROMPTER_API_KEY",
                "timeoutSeconds", 90L,
                "projectId", "some-project"),
            MODEL, Map.of());

        assertEquals("https://example.invalid/v1", settings.getEndpoint());
        assertEquals("PROMPTER_API_KEY", settings.getApiKeyEnvVar());
        assertEquals(90, settings.getTimeoutSeconds());
        assertEquals("some-project", settings.getProviderProperty("projectId"));
        assertNull(settings.getProviderProperty("absent"));
    }

    @Test
    void readsTheModelSettings()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER, Map.of(), MODEL,
            Map.of("maxOutputTokens", 2000L,
                "temperature", 0.7d,
                "contextLimitTokens", 131072L,
                "chunkTokenSize", 30000L,
                "wholeDocumentTokenLimit", 15000L,
                "developer", "openai"));

        assertEquals(2000, settings.getMaxOutputTokens());
        assertEquals(0.7d, settings.getTemperature());
        assertEquals(131072, settings.getContextLimitTokens());
        assertEquals(30000, settings.getChunkTokenSize());
        assertEquals(15000, settings.getWholeDocumentTokenLimit());
        assertEquals("openai", settings.getDeveloper());
        assertEquals("openai", settings.getModelProperty("developer"));
        assertNull(settings.getModelProperty("absent"));
    }

    @Test
    void appliesTheDocumentedDefaultsWhenAPropertyIsMissing()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER, Map.of(), MODEL, Map.of());

        assertEquals(120, settings.getTimeoutSeconds());
        assertEquals(1000, settings.getMaxOutputTokens());
        assertEquals(0.0d, settings.getTemperature());
        assertEquals(0, settings.getContextLimitTokens());
        assertEquals(0, settings.getChunkTokenSize());
        assertEquals(LLMSettings.DEFAULT_WHOLE_DOCUMENT_TOKEN_LIMIT, settings.getWholeDocumentTokenLimit());
        assertEquals(20000L, LLMSettings.DEFAULT_WHOLE_DOCUMENT_TOKEN_LIMIT);
    }

    @Test
    void coercesNumbersWrittenAsStrings()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER, Map.of("timeoutSeconds", "600"), MODEL,
            Map.of("maxOutputTokens", "2048", "temperature", "0.25"));

        assertEquals(600, settings.getTimeoutSeconds());
        assertEquals(2048, settings.getMaxOutputTokens());
        assertEquals(0.25d, settings.getTemperature());
    }

    @Test
    void fallsBackToTheDefaultWhenANumberCannotBeParsed()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER, Map.of("timeoutSeconds", "soon"), MODEL,
            Map.of("maxOutputTokens", "many", "temperature", "warm"));

        assertEquals(120, settings.getTimeoutSeconds());
        assertEquals(1000, settings.getMaxOutputTokens());
        assertEquals(0.0d, settings.getTemperature());
    }

    @Test
    void nonStringPropertiesAreReadThroughToString()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER, Map.of("endpoint", 42), MODEL,
            Map.of("developer", Boolean.TRUE));

        assertEquals("42", settings.getEndpoint());
        assertEquals("true", settings.getDeveloper());
    }

    @Test
    void copiesThePropertyMapsItWasGiven()
    {
        final Map<String, Object> providerProperties = new HashMap<>();
        providerProperties.put("endpoint", "https://example.invalid/v1");
        final Map<String, Object> modelProperties = new HashMap<>();
        modelProperties.put("developer", "meta");

        final LLMSettings settings = new LLMSettings(PROVIDER, providerProperties, MODEL, modelProperties);

        providerProperties.put("endpoint", "https://elsewhere.invalid/v1");
        modelProperties.remove("developer");

        assertEquals("https://example.invalid/v1", settings.getEndpoint());
        assertEquals("meta", settings.getDeveloper());
    }
}
