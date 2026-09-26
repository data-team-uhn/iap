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

import java.io.IOException;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for {@link LLMClient} implementations. It wires {@link LLMClient#chat} to a single
 * {@link #doChat(String, List, LLMRequestOptions)} hook that the concrete client implements, and holds
 * the {@link LLMConfigurationService} used to resolve the active {@link LLMSettings}. The transport, request
 * shaping and response parsing are entirely the subclass's concern
 * — {@link io.uhndata.iap.llm.internal.OpenAIClient} builds an OpenAI-compatible request through the
 * LangChain4j SDK.
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class DefaultLLMClient implements LLMClient
{
    /**
     * Where every model call is timed.
     *
     * <p>This is the one place every call passes through, whoever made it and whichever client answers it, so it
     * is the only place a stage's cost can be measured without each caller timing itself. The response schema's
     * name says which stage the call was - {@code iap_is_proposal_gate}, {@code iap_proposal_category},
     * {@code iap_intake} - so one log line per call is enough to tell where a reading spent its minutes.</p>
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLLMClient.class);

    /**
     * The configuration service used to resolve the active settings.
     *
     * <p>
     * Held here so every client shares one accessor. The {@code @Reference} that fills it lives on the
     * concrete {@code @Component}: DS will not inject a private field declared on this abstract class, even
     * in the same bundle, and the client would activate with a null against a {@code @NotNull} accessor.
     * </p>
     */
    private LLMConfigurationService configurationService;

    @Override
    @NotNull
    public String chat(@Nullable final String systemPrompt, @NotNull final List<LLMMessage> messages,
        @Nullable final LLMRequestOptions options) throws IOException
    {
        final String stage = options == null ? "none" : String.valueOf(options.getResponseSchemaName());
        final long promptChars = countCharacters(systemPrompt, messages);
        final long startedAt = System.nanoTime();
        try {
            final String reply = doChat(systemPrompt, messages, options);
            LOGGER.info("LLM call done: stage={} promptChars={} maxOutputTokens={} ms={} replyChars={}",
                stage, promptChars, options == null ? null : options.getMaxOutputTokens(),
                millisecondsSince(startedAt), reply.length());
            return reply;
        } catch (final IOException e) {
            LOGGER.warn("LLM call failed: stage={} promptChars={} ms={} reason={}",
                stage, promptChars, millisecondsSince(startedAt), e.getMessage());
            throw e;
        }
    }

    /**
     * How much text this call sends, which is what a provider charges for and what its prefill time scales with.
     *
     * @param systemPrompt the system prompt, may be {@code null}
     * @param messages the conversation turns
     * @return the total number of characters
     */
    private static long countCharacters(final String systemPrompt, final List<LLMMessage> messages)
    {
        long characters = systemPrompt == null ? 0 : systemPrompt.length();
        for (final LLMMessage message : messages) {
            characters += message.getContent().length();
        }
        return characters;
    }

    /**
     * How long ago something started, in milliseconds.
     *
     * @param startedAt the {@link System#nanoTime()} it started at
     * @return the elapsed milliseconds
     */
    protected static long millisecondsSince(final long startedAt)
    {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /**
     * The configuration service used to resolve the active settings, injected as an OSGi reference and shared
     * by all concrete clients.
     *
     * @return the configuration service
     */
    @NotNull
    protected LLMConfigurationService getConfigurationService()
    {
        return this.configurationService;
    }

    /**
     * Set the configuration service. The concrete client's {@code @Reference} bind method calls this; tests
     * that build a client outside OSGi call it themselves.
     *
     * @param service the configuration service to use
     */
    protected void setConfigurationService(@NotNull final LLMConfigurationService service)
    {
        this.configurationService = service;
    }

    /**
     * Send a conversation to the model and return its text reply. Concrete clients resolve the active settings
     * (via {@link #getConfigurationService()}), build and dispatch the request, and extract the reply.
     *
     * @param systemPrompt the optional system prompt (may be {@code null} or blank)
     * @param messages the conversation turns
     * @param options per-call overrides, or {@code null} to use the active model's settings unchanged
     * @return the assistant's reply
     * @throws IOException on configuration, network or API errors
     */
    @NotNull
    protected abstract String doChat(@Nullable String systemPrompt, @NotNull List<LLMMessage> messages,
        @Nullable LLMRequestOptions options) throws IOException;
}
