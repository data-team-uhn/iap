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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        return new ModelSettings(131072, 0.7, developer, extra);
    }

    @Test
    void keepsTheProviderName()
    {
        final LLMSettings settings = new LLMSettings(PROVIDER, provider(ENDPOINT, null), MODEL,
            model("openai", null));

        assertEquals(PROVIDER, settings.getProviderName());
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

        assertEquals(131072, settings.getContextLimitTokens());
        assertEquals(0.7d, settings.getTemperature());
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
        final ModelSettings settings = new ModelSettings(131072, 0.7, "openai", Map.of("k", "v"));
        final ModelSettings same = new ModelSettings(131072, 0.7, "openai", Map.of("k", "v"));

        assertEquals(settings, settings);
        assertEquals(settings, same);
        assertEquals(settings.hashCode(), same.hashCode());
        assertFalse(settings.equals(null));
        assertFalse(settings.equals("not a ModelSettings"));
        assertFalse(settings.equals(new ModelSettings(1, 0.7, "openai", Map.of("k", "v"))));
        assertFalse(settings.equals(new ModelSettings(131072, 0.1, "openai", Map.of("k", "v"))));
        assertFalse(settings.equals(new ModelSettings(131072, 0.7, "anthropic",
            Map.of("k", "v"))));
        assertFalse(settings.equals(new ModelSettings(131072, 0.7, "openai", Map.of("k", "w"))));
    }

    @Test
    void sendsTheModelIdWhenTheModelDeclaresOneAndTheNodeNameOtherwise()
    {
        // A JCR name has no colon in it, so an Ollama tag cannot be a node name.
        assertEquals("llama3.2-3b", settingsWithModelExtra(Map.of()).getModelId());
        assertEquals("llama3.2:3b",
            settingsWithModelExtra(Map.of("modelId", "llama3.2:3b")).getModelId());
        assertEquals("llama3.2-3b", settingsWithModelExtra(Map.of("modelId", "  ")).getModelId(),
            "a blank identifier is not an identifier");
    }

    @Test
    void comparesAMultiValuedExtraByValue()
    {
        final ProviderSettings first =
            new ProviderSettings("http://localhost", null, 10, Map.of("stops", new String[] { "a", "b" }));
        final ProviderSettings second =
            new ProviderSettings("http://localhost", null, 10, Map.of("stops", new String[] { "a", "b" }));

        assertEquals(first, second, "two reads of the same unchanged node");
        assertEquals(first.hashCode(), second.hashCode());
        assertFalse(first.equals(
            new ProviderSettings("http://localhost", null, 10, Map.of("stops", new String[] { "a", "c" }))));
    }

    @Test
    void settingsThatDifferHashDifferently()
    {
        // The equals/hashCode contract is satisfied by returning a constant, so the existing contract tests
        // stayed green with every hashCode replaced by 0. This is the half that does not.
        final LLMSettings settings = settingsWithModelExtra(Map.of());
        final LLMSettings other = new LLMSettings("prompter", new ProviderSettings("http://elsewhere", null, 10,
            Map.of()), "GPT-OSS-120B", new ModelSettings(1, 0.5, null, Map.of()));

        assertNotEquals(settings.hashCode(), other.hashCode());
        assertNotEquals(
            new ProviderSettings("http://a", null, 10, Map.of()).hashCode(),
            new ProviderSettings("http://b", null, 10, Map.of()).hashCode());
        assertNotEquals(
            new ModelSettings(1, 0.5, null, Map.of()).hashCode(),
            new ModelSettings(9, 0.5, null, Map.of()).hashCode());
    }

    private LLMSettings settingsWithModelExtra(final Map<String, Object> extra)
    {
        return new LLMSettings("local", new ProviderSettings("http://localhost", null, 10, Map.of()),
            "llama3.2-3b", new ModelSettings(131072, 0.0, "meta", extra));
    }
}
