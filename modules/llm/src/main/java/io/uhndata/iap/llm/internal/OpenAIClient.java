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
import org.osgi.service.component.annotations.Reference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.llm.DefaultLLMClient;
import io.uhndata.iap.llm.LLMCallGate;
import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;
import io.uhndata.iap.llm.LLMSettings;

/**
 * {@link LLMClient} for OpenAI-compatible chat completions endpoints (Prompter, Ollama, LM Studio, etc.),
 * registered for the {@code "openai"} API. Providers select it through their {@code api} property rather than by
 * name, so a single client serves every OpenAI-compatible provider. The request is dispatched with the
 * LangChain4j {@link OpenAiChatModel} (on its JDK-HTTP-client transport, wired explicitly to avoid an OSGi
 * {@code ServiceLoader} lookup): the configured endpoint becomes the model's base URL, the API key is sent as a
 * Bearer token, temperature / max-output-tokens / timeout come from the active model, and the OpenAI-specific
 * extras that LangChain4j does not model directly — a {@code response_format} JSON Schema for structured
 * outputs, {@code chat_template_kwargs.enable_thinking=false}, and an optional {@code project_id} — are passed
 * verbatim through the model's {@code customParameters} (serialized as top-level request fields). All settings
 * come from the active provider and model in the JCR LLM configuration.
 *
 * <p>Safe to call from many threads at once. Every call takes a slot from the {@link LLMCallGate} first, so
 * the provider never sees more requests from this instance than the gate allows.
 *
 * <p>The per-call options shape the request body, so one model is kept per settings/options pair. Every
 * model built for one provider shares one HTTP client, so they share one connection pool.
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

    /**
     * How many times a failed request is sent again. Once: a second retry after a read timeout is another two
     * minutes spent on an answer that was probably never coming, holding a call slot the whole time.
     */
    private static final int MAX_RETRIES = 1;

    /** How many models are kept. Step 2 builds one per batch of fields, and several batches run at once. */
    private static final int MAX_CACHED_MODELS = 16;

    /** How many HTTP clients are kept. One per provider; a second exists only while the provider changes. */
    private static final int MAX_CACHED_CLIENTS = 4;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** One HTTP client per read timeout, shared by every model built with it. */
    private final BoundedCache<Duration, HttpClient> httpClients = new BoundedCache<>(MAX_CACHED_CLIENTS);

    /** One model per settings and options, since the options shape the request body. */
    private final BoundedCache<ModelKey, OpenAiChatModel> models = new BoundedCache<>(MAX_CACHED_MODELS);

    private LLMCallGate callGate;

    @Reference
    void bindConfigurationService(final LLMConfigurationService service)
    {
        setConfigurationService(service);
    }

    @Reference
    void bindCallGate(final LLMCallGate gate)
    {
        this.callGate = gate;
    }

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
            final OpenAiChatModel model = modelFor(settings, options);
            // Waiting longer than the read timeout for a slot means the provider is already behind; failing
            // fast is better than queueing behind it.
            final LLMCallGate.Permit permit = this.callGate.acquire(Duration.ofSeconds(settings.getTimeoutSeconds()));
            try {
                final ChatResponse response = model.chat(toChatMessages(systemPrompt, messages));
                return response.aiMessage().text();
            } finally {
                permit.close();
            }
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
     * How many models are kept right now. For tests.
     *
     * @return the count
     */
    int countModels()
    {
        return this.models.size();
    }

    /**
     * How many HTTP clients are kept right now. For tests.
     *
     * @return the count
     */
    int countHttpClients()
    {
        return this.httpClients.size();
    }

    private OpenAiChatModel modelFor(final LLMSettings settings, final LLMRequestOptions options)
    {
        return this.models.get(new ModelKey(settings, options), key -> buildModel(key.settings(), key.options()));
    }

    private OpenAiChatModel buildModel(final LLMSettings settings, final LLMRequestOptions options)
    {
        final long maxTokens = options == null
            ? settings.getMaxOutputTokens() : options.resolveMaxOutputTokens(settings.getMaxOutputTokens());
        final Duration readTimeout = Duration.ofSeconds(settings.getTimeoutSeconds());
        final OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            .httpClientBuilder(new SharedHttpClientBuilder(httpClientFor(readTimeout), CONNECT_TIMEOUT, readTimeout))
            .maxRetries(MAX_RETRIES)
            .baseUrl(resolveBaseUrl(settings.getEndpoint()))
            .modelName(settings.getModelName())
            .temperature(settings.getTemperature())
            .maxTokens((int) maxTokens)
            .customParameters(customParameters(settings, options));
        final String apiKey = resolveApiKey(settings);
        if (StringUtils.isNotBlank(apiKey)) {
            builder.apiKey(apiKey);
        }
        return builder.build();
    }

    /** The one HTTP client for this read timeout. The JDK client pools connections per host on its own. */
    private HttpClient httpClientFor(final Duration readTimeout)
    {
        return this.httpClients.get(readTimeout, timeout -> new JdkHttpClientBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .readTimeout(timeout)
            .build());
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

    private String resolveApiKey(final LLMSettings settings)
    {
        final String apiKeyEnvVar = settings.getApiKeyEnvVar();
        return StringUtils.isNotBlank(apiKeyEnvVar) ? environment(apiKeyEnvVar) : null;
    }

    /**
     * The OpenAI-compatible request extras carried verbatim as top-level body fields: always
     * {@code chat_template_kwargs.enable_thinking=false}, an optional {@code project_id}, and a
     * {@code response_format} JSON Schema when the call requests structured output.
     *
     * @param settings the active settings
     * @param options the per-call options, or {@code null}
     * @return the custom-parameters map for the LangChain4j model
     */
    private static Map<String, Object> customParameters(final LLMSettings settings, final LLMRequestOptions options)
    {
        final Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_template_kwargs", Collections.singletonMap("enable_thinking", Boolean.FALSE));
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
     * What shapes a model: the settings and the per-call options. Two calls with equal keys get one model.
     *
     * @param settings the active settings
     * @param options the per-call options, or {@code null}
     * @version $Id$
     * @since 0.1.0
     */
    private record ModelKey(LLMSettings settings, LLMRequestOptions options)
    {
    }
}
