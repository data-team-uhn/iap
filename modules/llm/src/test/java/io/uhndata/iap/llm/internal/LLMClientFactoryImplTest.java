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
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;
import io.uhndata.iap.llm.LLMSettings;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LLMClientFactoryImpl}: which client serves a provider, and how an unresolvable
 * provider is reported.
 *
 * @version $Id$
 * @since 0.1.0
 */
class LLMClientFactoryImplTest
{
    private static final String PROVIDER_PROPERTY = "llm.provider";

    private static final String OPENAI = "openai";

    private LLMClientFactoryImpl factory;

    private LLMClient openAiClient;

    /**
     * A client that answers with a fixed reply, so the tests can tell the instances apart.
     */
    private static final class StubClient implements LLMClient
    {
        private final String reply;

        StubClient(final String reply)
        {
            this.reply = reply;
        }

        @Override
        public String chat(final String userMessage)
        {
            return this.reply;
        }

        @Override
        public String chat(final String systemPrompt, final String userMessage)
        {
            return this.reply;
        }

        @Override
        public String chat(final String systemPrompt, final List<LLMMessage> messages)
        {
            return this.reply;
        }

        @Override
        public String chat(final String systemPrompt, final List<LLMMessage> messages,
            final LLMRequestOptions options)
        {
            return this.reply;
        }
    }

    private static LLMSettings settings(final String providerName, final String api)
    {
        return new LLMSettings(providerName, api == null ? Map.of() : Map.of("api", api), "a-model", Map.of());
    }

    private static void inject(final Object target, final String fieldName, final Object value) throws Exception
    {
        final Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @BeforeEach
    void setUp() throws Exception
    {
        this.factory = new LLMClientFactoryImpl();
        this.openAiClient = new StubClient("from openai");
        this.factory.bindClient(this.openAiClient, Map.of(PROVIDER_PROPERTY, OPENAI));
        inject(this.factory, "configurationService",
            (LLMConfigurationService) () -> settings("local", OPENAI));
    }

    @Test
    void handsOutTheClientRegisteredForAProvider()
    {
        assertSame(this.openAiClient, this.factory.getClient(OPENAI));
    }

    @Test
    void hasNoClientForAnUnknownOrMissingProvider()
    {
        assertNull(this.factory.getClient("anthropic"));
        assertNull(this.factory.getClient(null));
    }

    @Test
    void forgetsAClientThatGoesAway()
    {
        this.factory.unbindClient(this.openAiClient, Map.of(PROVIDER_PROPERTY, OPENAI));
        assertNull(this.factory.getClient(OPENAI));
    }

    @Test
    void ignoresAClientRegisteredWithoutAProviderName()
    {
        final LLMClient nameless = new StubClient("nameless");
        this.factory.bindClient(nameless, Map.of());
        this.factory.unbindClient(nameless, Map.of());
        assertSame(this.openAiClient, this.factory.getClient(OPENAI));
    }

    @Test
    void resolvesTheActiveClientThroughTheProviderApiProperty() throws IOException
    {
        assertSame(this.openAiClient, this.factory.getActiveClient());
    }

    @Test
    void fallsBackToTheProviderNameWhenItDeclaresNoApi() throws Exception
    {
        final LLMClient named = new StubClient("by name");
        this.factory.bindClient(named, Map.of(PROVIDER_PROPERTY, "ollama"));
        inject(this.factory, "configurationService", (LLMConfigurationService) () -> settings("ollama", null));

        assertSame(named, this.factory.getActiveClient());
    }

    @Test
    void failsWhenNoClientServesTheActiveProvider() throws Exception
    {
        inject(this.factory, "configurationService",
            (LLMConfigurationService) () -> settings("claude", "anthropic"));

        final IOException failure = assertThrows(IOException.class, () -> this.factory.getActiveClient());
        assertTrue(failure.getMessage().contains("'claude'"));
        assertTrue(failure.getMessage().contains("anthropic"));
        assertTrue(failure.getMessage().contains(OPENAI), "lists the providers that are registered");
    }

    @Test
    void failsWhenTheActiveProviderResolvesToNoLookupKey() throws Exception
    {
        inject(this.factory, "configurationService", (LLMConfigurationService) () -> settings(null, null));

        assertThrows(IOException.class, () -> this.factory.getActiveClient());
    }
}
