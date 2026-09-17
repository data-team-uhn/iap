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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.annotations.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.llm.DefaultLLMClient;
import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;
import io.uhndata.iap.llm.LLMSettings;

/**
 * {@link LLMClient} for OpenAI-compatible chat completions endpoints (Prompter, Ollama, LM Studio, etc.),
 * registered for the {@code "openai"} API. Providers select it through their {@code api} property rather than by
 * name, so a single client serves every OpenAI-compatible provider. The request is dispatched with the
 * LangChain4j {@link OpenAiChatModel}, on its JDK-HTTP-client transport, wired explicitly to avoid an OSGi
 * {@code ServiceLoader} lookup.
 *
 * <p>
 * The provider supplies the endpoint, which becomes the model's base URL, the API key, sent as a Bearer
 * token, and the request timeout. The model supplies the identifier to send, the temperature and the
 * max-output-tokens ceiling. Three OpenAI-compatible extras LangChain4j does not model directly ride on
 * the request's {@code customParameters}, which are serialized as top-level request fields: a
 * {@code response_format} JSON Schema for structured outputs, an optional
 * {@code chat_template_kwargs.enable_thinking=false}, and an optional {@code project_id}.
 * </p>
 *
 * <p>
 * Building an {@link OpenAiChatModel} sets up its own HTTP client, so the last one built is kept and reused
 * while the settings that shaped it are unchanged. Everything that varies per call travels on the request
 * rather than the model, so a caller passing options does not evict the model the next caller needs.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(
    service = LLMClient.class,
    property = { "llm.provider=openai" },
    immediate = true)
public class OpenAIClient extends DefaultLLMClient
{
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private static final String PROJECT_ID = "projectId";

    /**
     * How long to wait for the TCP connection to the provider to be established. Kept short and separate from
     * the read timeout (the active provider's configured {@code timeoutSeconds}, which for a slow model can
     * legitimately run to several minutes): without this, a provider that accepts a connection but never
     * completes it ties up the calling thread for as long as the read timeout allows, and since this call runs
     * synchronously inside a request to this server, enough stuck providers exhaust its thread pool.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** The provider property asking for {@code chat_template_kwargs.enable_thinking=false}. */
    private static final String DISABLE_THINKING = "disableThinking";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** The model built for the most recent settings, reused while they are unchanged. */
    private volatile CachedModel cachedModel;

    /**
     * Read an environment variable. Overridden in tests, which cannot set one.
     *
     * @param name the name of the variable to read
     * @return the value, or {@code null} when the variable is not set
     */
    protected String environment(final String name)
    {
        return System.getenv(name);
    }

    @Override
    protected String doChat(final String systemPrompt, final List<LLMMessage> messages,
        final LLMRequestOptions options) throws IOException
    {
        try {
            final LLMSettings settings = getConfigurationService().getActiveSettings();
            final ChatRequest request = ChatRequest.builder()
                .messages(toChatMessages(systemPrompt, messages))
                .parameters(requestParameters(settings, options))
                .build();
            final ChatResponse response = modelFor(settings).chat(request);
            final String reply = response.aiMessage().text();
            if (reply == null) {
                // LangChain4j leaves the text null when the provider answered with empty content, which a
                // reasoning model that spends its whole budget before saying anything visible does. This
                // method is declared @NotNull, so the caller is entitled to assume it never sees one.
                throw new IOException("The LLM returned an empty answer; the model may have reached its "
                    + "output-token limit before producing any visible content");
            }
            return reply;
        } catch (final IOException e) {
            ErrorLogger.logError(e, ErrorContext.of(OpenAIClient.class, "doChat"));
            throw e;
        } catch (final RuntimeException e) {
            // LangChain4j signals transport / HTTP / provider errors with runtime exceptions; the pipeline
            // expects an IOException it can surface to the servlet, so translate rather than let it escape raw.
            ErrorLogger.logError(e, ErrorContext.of(OpenAIClient.class, "doChat"));
            throw new IOException("OpenAI-compatible LLM request failed: " + e.getMessage(), e);
        }
    }

    /**
     * The model built for these settings, reusing the previous one while they are unchanged. Not
     * synchronized: two concurrent calls that both miss the cache each build their own model, which is
     * wasted work but not a correctness problem, and cheaper than serializing every call through a lock for
     * the common case where nothing has changed.
     *
     * @param settings the active settings
     * @return a model matching these settings
     */
    private OpenAiChatModel modelFor(final LLMSettings settings)
    {
        final CachedModel current = this.cachedModel;
        if (current != null && current.settings.equals(settings)) {
            return current.model;
        }
        final OpenAiChatModel model = buildModel(settings);
        this.cachedModel = new CachedModel(settings, model);
        return model;
    }

    /**
     * The parameters that vary per call, carried on the request rather than baked into the model.
     *
     * @param settings the active settings
     * @param options the per-call options, or {@code null}
     * @return the request parameters
     */
    private static ChatRequestParameters requestParameters(final LLMSettings settings,
        final LLMRequestOptions options)
    {
        final long maxTokens = options == null
            ? settings.getMaxOutputTokens() : options.resolveMaxOutputTokens(settings.getMaxOutputTokens());
        return OpenAiChatRequestParameters.builder()
            .maxOutputTokens(clampToInt(maxTokens))
            .customParameters(customParameters(settings, options))
            .build();
    }

    /**
     * The token ceiling as an {@code int}, which is what the OpenAI request field is.
     *
     * <p>
     * The configured value is a JCR {@code LONG}, so it can exceed the range: casting straight turned
     * 4294967296 into 0 and 3000000000 into a negative, which the provider refuses with a 400 that reaches
     * the caller as a 502, with nothing to suggest the configured number was truncated.
     * </p>
     *
     * @param maxTokens the configured ceiling
     * @return the ceiling clamped to the int range
     * @throws IllegalArgumentException when the ceiling is not positive
     */
    private static int clampToInt(final long maxTokens)
    {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive; got " + maxTokens);
        }
        return (int) Math.min(maxTokens, Integer.MAX_VALUE);
    }

    private OpenAiChatModel buildModel(final LLMSettings settings)
    {
        final Duration readTimeout = Duration.ofSeconds(settings.getTimeoutSeconds());
        final HttpClientBuilder httpClientBuilder = new PinnedConnectTimeout(
            new JdkHttpClientBuilder().connectTimeout(CONNECT_TIMEOUT).readTimeout(readTimeout));
        final OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            // LangChain4j reads this field, not the HTTP client builder's: OpenAiChatModel's constructor
            // passes getOrDefault(timeout, 60s) down to its client, which prefers it over whatever the
            // builder carries -- so leaving it unset capped every call at 60 seconds however long the
            // provider was configured for.
            .timeout(readTimeout)
            .baseUrl(resolveBaseUrl(settings.getEndpoint()))
            .modelName(settings.getModelId())
            .temperature(settings.getTemperature());
        final String apiKey = resolveApiKey(settings);
        if (StringUtils.isNotBlank(apiKey)) {
            builder.apiKey(apiKey);
        }
        return builder.build();
    }

    /**
     * The base URL LangChain4j appends {@code /chat/completions} to. The configured endpoint may already carry
     * that suffix (the previous client appended it explicitly); strip it here so the final URL is unchanged.
     *
     * @param endpoint the configured provider endpoint
     * @return the base URL without a trailing {@code /chat/completions} or slash
     */
    private static String resolveBaseUrl(final String endpoint)
    {
        final String trimmed = StringUtils.stripEnd(StringUtils.trimToEmpty(endpoint), "/");
        if (trimmed.endsWith(CHAT_COMPLETIONS_PATH)) {
            return trimmed.substring(0, trimmed.length() - CHAT_COMPLETIONS_PATH.length());
        }
        return trimmed;
    }

    /**
     * The API key for this provider, or {@code null} when it names no environment variable.
     *
     * <p>
     * Naming a variable that is not exported is a different thing from naming none, and only one of them is
     * a mistake. Sending the request anyway got a 401 back from the provider and told the user the request
     * could not be completed, which reads exactly like the provider being down.
     * </p>
     *
     * @param settings the active settings
     * @return the key, or {@code null} when this provider needs none
     * @throws IllegalStateException when the named variable is not set
     */
    private String resolveApiKey(final LLMSettings settings)
    {
        final String apiKeyEnvVar = settings.getApiKeyEnvVar();
        if (StringUtils.isBlank(apiKeyEnvVar)) {
            return null;
        }
        final String key = environment(apiKeyEnvVar);
        if (StringUtils.isBlank(key)) {
            throw new IllegalStateException("The LLM provider is configured to read its API key from "
                + apiKeyEnvVar + ", and that environment variable is not set");
        }
        return key;
    }

    /**
     * The OpenAI-compatible request extras carried verbatim as top-level body fields: an optional
     * {@code chat_template_kwargs.enable_thinking=false}, an optional {@code project_id}, and a
     * {@code response_format} JSON Schema when the call requests structured output.
     *
     * <p>
     * The thinking kwarg is sent only for a provider whose {@code disableThinking} says it understands one:
     * it is a vLLM extension, and OpenAI itself refuses an unknown top-level argument with a 400, so sending
     * it unconditionally from the client named for OpenAI-compatible endpoints locked OpenAI out.
     * </p>
     *
     * @param settings the active settings
     * @param options the per-call options, or {@code null}
     * @return the custom-parameters map for the request
     */
    private static Map<String, Object> customParameters(final LLMSettings settings, final LLMRequestOptions options)
    {
        final Map<String, Object> params = new LinkedHashMap<>();
        if (Boolean.parseBoolean(settings.getProviderProperty(DISABLE_THINKING))) {
            params.put("chat_template_kwargs", Collections.singletonMap("enable_thinking", Boolean.FALSE));
        }
        final String projectId = settings.getProviderProperty(PROJECT_ID);
        if (StringUtils.isNotBlank(projectId)) {
            params.put("project_id", projectId);
        }
        if (options != null && options.hasResponseSchema()) {
            params.put("response_format", responseFormat(options));
        }
        return params;
    }

    /**
     * Build the OpenAI {@code response_format} object pinning the reply to a JSON Schema (structured outputs):
     * {@code {"type":"json_schema","json_schema":{"name":...,"strict":true,"schema":{...}}}}.
     *
     * @param options the per-call options carrying the schema name and body
     * @return the {@code response_format} value as a nested map
     */
    private static Map<String, Object> responseFormat(final LLMRequestOptions options)
    {
        final Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", options.getResponseSchemaName());
        jsonSchema.put("strict", Boolean.TRUE);
        jsonSchema.put("schema", parseSchema(options.getResponseSchema()));
        final Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("json_schema", jsonSchema);
        return format;
    }

    /**
     * Parse a JSON Schema string into plain Java objects (maps, lists, strings, numbers, booleans, null) so
     * LangChain4j's Jackson serializer emits it as native nested JSON in the request body, rather than escaping
     * it as a string.
     *
     * @param schema the JSON Schema, as raw JSON text
     * @return the equivalent plain Java object
     */
    private static Object parseSchema(final String schema)
    {
        try {
            return OBJECT_MAPPER.readValue(schema, Object.class);
        } catch (final JsonProcessingException e) {
            throw new IllegalArgumentException("The response schema is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static List<ChatMessage> toChatMessages(final String systemPrompt, final List<LLMMessage> messages)
    {
        final List<ChatMessage> turns = new ArrayList<>();
        if (StringUtils.isNotBlank(systemPrompt)) {
            turns.add(SystemMessage.from(systemPrompt));
        }
        for (final LLMMessage message : messages) {
            turns.add(toChatMessage(message));
        }
        return turns;
    }

    private static ChatMessage toChatMessage(final LLMMessage message)
    {
        final String role = message.getRole();
        if ("assistant".equalsIgnoreCase(role)) {
            return AiMessage.from(message.getContent());
        }
        if ("system".equalsIgnoreCase(role)) {
            return SystemMessage.from(message.getContent());
        }
        return UserMessage.from(message.getContent());
    }

    /**
     * The model built for one set of settings, kept so unchanged settings do not rebuild it.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class CachedModel
    {
        private final LLMSettings settings;

        private final OpenAiChatModel model;

        CachedModel(final LLMSettings settings, final OpenAiChatModel model)
        {
            this.settings = settings;
            this.model = model;
        }
    }

    /**
     * An HTTP client builder whose connect timeout cannot be overwritten.
     *
     * <p>
     * {@link OpenAiChatModel} has one {@code timeout} and sets both the connect and the read timeout from it,
     * overwriting whatever this builder already carried -- so asking for a long read timeout asked for an
     * equally long connect timeout, and there is no second field to say otherwise. Ignoring the connect
     * override keeps {@link #CONNECT_TIMEOUT} short, which is the point of having it: a provider that accepts
     * a connection and never completes it would otherwise hold the calling thread for the read timeout.
     * </p>
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class PinnedConnectTimeout implements HttpClientBuilder
    {
        private final HttpClientBuilder delegate;

        PinnedConnectTimeout(final HttpClientBuilder delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public Duration connectTimeout()
        {
            return this.delegate.connectTimeout();
        }

        @Override
        public HttpClientBuilder connectTimeout(final Duration ignored)
        {
            return this;
        }

        @Override
        public Duration readTimeout()
        {
            return this.delegate.readTimeout();
        }

        @Override
        public HttpClientBuilder readTimeout(final Duration timeout)
        {
            this.delegate.readTimeout(timeout);
            return this;
        }

        @Override
        public HttpClient build()
        {
            return this.delegate.build();
        }
    }
}
