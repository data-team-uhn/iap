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

import io.uhndata.iap.llm.LLMSettings.ModelSettings;
import io.uhndata.iap.llm.LLMSettings.ProviderSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link LLMSettings}: that it names its provider and model correctly and delegates every
 * getter to the {@link ProviderSettings} / {@link ModelSettings} it was built from. Where those values come
 * from — including the CND defaults and the coercion of JCR property values that arrive as strings — is
 * {@link io.uhndata.iap.llm.internal.models.LLMProviderNodeTest} and
 * {@link io.uhndata.iap.llm.internal.models.LLMModelNodeTest}: a settings snapshot never reads a repository
 * itself, so there is nothing left for a "missing property" or "value written as a string" test to exercise
 * here.
 *
 * @version $Id$
 * @since 0.1.0
 */
class LLMSettingsTest
{
    private static final String PROVIDER = "prompter";

    private static final String MODEL = "GPT-OSS-120B";

    private static final String ENDPOINT = "https://example.invalid/v1";

    private static ProviderSettings provider(final String endpoint, final Map<String, Object> extra)
    {
        return new ProviderSettings(endpoint, "PROMPTER_API_KEY", 90, extra);
    }

    private static ModelSettings model(final String developer, final Map<String, Object> extra)
    {
        return new ModelSettings(131072, 2000, 0.7, 30000, 15000, developer, extra);
    }

    @Test
    void keepsTheProviderAndModelNames()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER, provider(ENDPOINT, null), MODEL,
            model("openai", null));

        assertEquals(PROVIDER, settings.getProviderName());
        assertEquals(MODEL, settings.getModelName());
    }

    @Test
    void delegatesTheProviderGetters()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER, provider(ENDPOINT, Map.of("projectId", "some-project")),
            MODEL, model("openai", null));

        assertEquals(ENDPOINT, settings.getEndpoint());
        assertEquals("PROMPTER_API_KEY", settings.getApiKeyEnvVar());
        assertEquals(90, settings.getTimeoutSeconds());
        assertEquals("some-project", settings.getProviderProperty("projectId"));
        assertNull(settings.getProviderProperty("absent"));
    }

    @Test
    void delegatesTheModelGetters()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER, provider(ENDPOINT, null), MODEL,
            model("openai", Map.of("tuned", true)));

        assertEquals(2000, settings.getMaxOutputTokens());
        assertEquals(0.7d, settings.getTemperature());
        assertEquals(131072, settings.getContextLimitTokens());
        assertEquals(30000, settings.getChunkTokenSize());
        assertEquals(15000, settings.getWholeDocumentTokenLimit());
        assertEquals("openai", settings.getDeveloper());
        assertEquals("true", settings.getModelProperty("tuned"));
        assertNull(settings.getModelProperty("absent"));
    }

    @Test
    void providerSettingsCopiesTheExtraMapItWasGiven()
    {
        final Map<String, Object> extra = new HashMap<>();
        extra.put("projectId", "some-project");
        final ProviderSettings settings = provider(ENDPOINT, extra);

        extra.put("projectId", "elsewhere");

        assertEquals("some-project", settings.getProperty("projectId"));
    }

    @Test
    void modelSettingsCopiesTheExtraMapItWasGiven()
    {
        final Map<String, Object> extra = new HashMap<>();
        extra.put("tuned", true);
        final ModelSettings settings = model("openai", extra);

        extra.remove("tuned");

        assertEquals("true", settings.getProperty("tuned"));
    }

    @Test
    void settingsEqualsAndHashCodeConsiderEveryField()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER, provider(ENDPOINT, null), MODEL,
            model("openai", null));
        final LLMSettings same = new LLMSettings(PROVIDER, provider(ENDPOINT, null), MODEL, model("openai", null));

        assertEquals(settings, settings);
        assertEquals(settings, same);
        assertEquals(settings.hashCode(), same.hashCode());
        assertFalse(settings.equals(null));
        assertFalse(settings.equals("not an LLMSettings"));
        assertFalse(settings.equals(new LLMSettings("other-provider", provider(ENDPOINT, null), MODEL,
            model("openai", null))));
        assertFalse(settings.equals(new LLMSettings(PROVIDER, provider(ENDPOINT, null), "other-model",
            model("openai", null))));
        assertFalse(settings.equals(new LLMSettings(PROVIDER, provider("https://elsewhere.invalid/v1", null), MODEL,
            model("openai", null))));
        assertFalse(settings.equals(new LLMSettings(PROVIDER, provider(ENDPOINT, null), MODEL,
            model("anthropic", null))));
    }

    @Test
    void providerSettingsEqualsAndHashCodeConsiderEveryField()
    {
        final ProviderSettings settings = new ProviderSettings(ENDPOINT, "KEY_VAR", 90, Map.of("projectId", "p"));
        final ProviderSettings same = new ProviderSettings(ENDPOINT, "KEY_VAR", 90, Map.of("projectId", "p"));

        assertEquals(settings, settings);
        assertEquals(settings, same);
        assertEquals(settings.hashCode(), same.hashCode());
        assertFalse(settings.equals(null));
        assertFalse(settings.equals("not a ProviderSettings"));
        assertFalse(settings.equals(new ProviderSettings("https://elsewhere.invalid", "KEY_VAR", 90,
            Map.of("projectId", "p"))));
        assertFalse(settings.equals(new ProviderSettings(ENDPOINT, "OTHER_VAR", 90, Map.of("projectId", "p"))));
        assertFalse(settings.equals(new ProviderSettings(ENDPOINT, "KEY_VAR", 60, Map.of("projectId", "p"))));
        assertFalse(settings.equals(new ProviderSettings(ENDPOINT, "KEY_VAR", 90, Map.of("projectId", "other"))));
    }

    @Test
    void modelSettingsEqualsAndHashCodeConsiderEveryField()
    {
        final ModelSettings settings = new ModelSettings(131072, 2000, 0.7, 30000, 15000, "openai", Map.of("k", "v"));
        final ModelSettings same = new ModelSettings(131072, 2000, 0.7, 30000, 15000, "openai", Map.of("k", "v"));

        assertEquals(settings, settings);
        assertEquals(settings, same);
        assertEquals(settings.hashCode(), same.hashCode());
        assertFalse(settings.equals(null));
        assertFalse(settings.equals("not a ModelSettings"));
        assertFalse(settings.equals(new ModelSettings(1, 2000, 0.7, 30000, 15000, "openai", Map.of("k", "v"))));
        assertFalse(settings.equals(new ModelSettings(131072, 1, 0.7, 30000, 15000, "openai", Map.of("k", "v"))));
        assertFalse(settings.equals(new ModelSettings(131072, 2000, 0.1, 30000, 15000, "openai", Map.of("k", "v"))));
        assertFalse(settings.equals(new ModelSettings(131072, 2000, 0.7, 1, 15000, "openai", Map.of("k", "v"))));
        assertFalse(settings.equals(new ModelSettings(131072, 2000, 0.7, 30000, 1, "openai", Map.of("k", "v"))));
        assertFalse(settings.equals(new ModelSettings(131072, 2000, 0.7, 30000, 15000, "anthropic",
            Map.of("k", "v"))));
        assertFalse(settings.equals(new ModelSettings(131072, 2000, 0.7, 30000, 15000, "openai", Map.of("k", "w"))));
    }
}
