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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of the settings for the active LLM provider and model, resolved from the JCR
 * configuration. A provider carries connection-level settings (endpoint, credentials, timeout) plus
 * format-specific extras (such as {@code projectId} for Prompter), while a model carries generation
 * settings (the model identifier, token limits and temperature). {@link ProviderSettings} and
 * {@link ModelSettings} carry the two halves; this class only pairs them with the node names they came from.
 *
 * <p>
 * A snapshot never needs a live JCR resource to exist: everything it can answer is copied in at construction.
 * {@link io.uhndata.iap.llm.internal.LLMConfigurationServiceImpl} is the one place that resolves a snapshot
 * from the repository, reading the provider and model nodes through the Sling Models in
 * {@code io.uhndata.iap.llm.internal.models} and copying their fields in; everywhere else, including every
 * test in this module, builds a snapshot directly.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class LLMSettings
{
    /**
     * Default {@code wholeDocumentTokenLimit} when a model node omits the property. Matches the historic
     * chunker {@code min_structure_tokens} default so CLI-only runs stay aligned with configured models.
     */
    public static final long DEFAULT_WHOLE_DOCUMENT_TOKEN_LIMIT = 20000L;

    private final String providerName;

    private final String modelName;

    private final ProviderSettings provider;

    private final ModelSettings model;

    /**
     * Create a settings snapshot.
     *
     * @param providerName the name of the active provider node
     * @param provider the settings of the active provider node
     * @param modelName the name of the active model node
     * @param model the settings of the active model node
     */
    public LLMSettings(@NotNull final String providerName, @NotNull final ProviderSettings provider,
        @NotNull final String modelName, @NotNull final ModelSettings model)
    {
        this.providerName = providerName;
        this.provider = provider;
        this.modelName = modelName;
        this.model = model;
    }

    /**
     * The name of the active provider node.
     *
     * @return the provider node name
     */
    @NotNull
    public String getProviderName()
    {
        return this.providerName;
    }

    /**
     * The name of the active model node.
     *
     * @return the model node name
     */
    @NotNull
    public String getModelName()
    {
        return this.modelName;
    }

    /**
     * The base URL of the active provider's API.
     *
     * @return the endpoint URL, or {@code null} if not set
     */
    @Nullable
    public String getEndpoint()
    {
        return this.provider.getEndpoint();
    }

    /**
     * The name of the environment variable holding the active provider's API key.
     *
     * @return the environment variable name, or {@code null} if not set
     */
    @Nullable
    public String getApiKeyEnvVar()
    {
        return this.provider.getApiKeyEnvVar();
    }

    /**
     * The request timeout for the active provider, in seconds.
     *
     * @return the timeout in seconds
     */
    public long getTimeoutSeconds()
    {
        return this.provider.getTimeoutSeconds();
    }

    /**
     * The maximum number of tokens to generate in the response for the active model.
     *
     * @return the maximum output tokens
     */
    public long getMaxOutputTokens()
    {
        return this.model.getMaxOutputTokens();
    }

    /**
     * The sampling temperature for the active model.
     *
     * @return the temperature
     */
    public double getTemperature()
    {
        return this.model.getTemperature();
    }

    /**
     * The maximum context window of the active model, in tokens.
     *
     * @return the context limit in tokens
     */
    public long getContextLimitTokens()
    {
        return this.model.getContextLimitTokens();
    }

    /**
     * The number of input tokens to send per chunk when the input exceeds the context window.
     *
     * @return the chunk token size
     */
    public long getChunkTokenSize()
    {
        return this.model.getChunkTokenSize();
    }

    /**
     * The document-size threshold, in estimated tokens ({@code chars / 4}), below which an uploaded document is
     * treated as small: it is never chunked and is sent to the model whole. This is the single source of the
     * small-document routing decision: the document parser receives it as its {@code min_structure_tokens}
     * parameter, which decides whether the document is chunked at all.
     *
     * @return the whole-document token limit
     */
    public long getWholeDocumentTokenLimit()
    {
        return this.model.getWholeDocumentTokenLimit();
    }

    /**
     * The organization that developed the active model (e.g. {@code google}, {@code anthropic}, {@code alibaba}).
     *
     * @return the developer name, or {@code null} if not set
     */
    @Nullable
    public String getDeveloper()
    {
        return this.model.getDeveloper();
    }

    /**
     * Read an arbitrary, format-specific property of the active provider (such as {@code projectId} or
     * {@code apiVersion}).
     *
     * @param name the property name
     * @return the property value as a string, or {@code null} if not set
     */
    @Nullable
    public String getProviderProperty(@NotNull final String name)
    {
        return this.provider.getProperty(name);
    }

    /**
     * Read an arbitrary, format-specific property of the active model.
     *
     * @param name the property name
     * @return the property value as a string, or {@code null} if not set
     */
    @Nullable
    public String getModelProperty(@NotNull final String name)
    {
        return this.model.getProperty(name);
    }

    @Override
    public boolean equals(final Object other)
    {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LLMSettings)) {
            return false;
        }
        final LLMSettings that = (LLMSettings) other;
        return this.providerName.equals(that.providerName) && this.modelName.equals(that.modelName)
            && this.provider.equals(that.provider) && this.model.equals(that.model);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(this.providerName, this.modelName, this.provider, this.model);
    }

    /**
     * The connection-level settings of one LLM provider: endpoint, credentials, timeout, plus whatever
     * format-specific extras it carries. Instances are immutable.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class ProviderSettings
    {
        private final String endpoint;

        private final String apiKeyEnvVar;

        private final long timeoutSeconds;

        private final Map<String, Object> extra;

        /**
         * Create a provider settings snapshot.
         *
         * @param endpoint the base URL of the provider's API, or {@code null} if not set
         * @param apiKeyEnvVar the name of the environment variable holding the API key, or {@code null} if not set
         * @param timeoutSeconds the request timeout, in seconds
         * @param extra format-specific extras with no dedicated field of their own (such as {@code projectId}),
         *            or {@code null} for none
         */
        public ProviderSettings(@Nullable final String endpoint, @Nullable final String apiKeyEnvVar,
            final long timeoutSeconds, @Nullable final Map<String, Object> extra)
        {
            this.endpoint = endpoint;
            this.apiKeyEnvVar = apiKeyEnvVar;
            this.timeoutSeconds = timeoutSeconds;
            this.extra = extra == null ? Collections.emptyMap() : new HashMap<>(extra);
        }

        /**
         * The base URL of this provider's API.
         *
         * @return the endpoint URL, or {@code null} if not set
         */
        @Nullable
        public String getEndpoint()
        {
            return this.endpoint;
        }

        /**
         * The name of the environment variable holding this provider's API key.
         *
         * @return the environment variable name, or {@code null} if not set
         */
        @Nullable
        public String getApiKeyEnvVar()
        {
            return this.apiKeyEnvVar;
        }

        /**
         * The request timeout for this provider, in seconds.
         *
         * @return the timeout in seconds
         */
        public long getTimeoutSeconds()
        {
            return this.timeoutSeconds;
        }

        /**
         * Read an arbitrary, format-specific property (such as {@code projectId} or {@code apiVersion}) that has
         * no dedicated field of its own.
         *
         * @param name the property name
         * @return the property value as a string, or {@code null} if not set
         */
        @Nullable
        public String getProperty(@NotNull final String name)
        {
            final Object value = this.extra.get(name);
            return value == null ? null : value.toString();
        }

        @Override
        public boolean equals(final Object other)
        {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ProviderSettings)) {
                return false;
            }
            final ProviderSettings that = (ProviderSettings) other;
            return this.timeoutSeconds == that.timeoutSeconds && Objects.equals(this.endpoint, that.endpoint)
                && Objects.equals(this.apiKeyEnvVar, that.apiKeyEnvVar) && this.extra.equals(that.extra);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(this.endpoint, this.apiKeyEnvVar, this.timeoutSeconds, this.extra);
        }
    }

    /**
     * The generation settings of one model offered by a provider: token limits, temperature, chunking
     * thresholds, plus whatever format-specific extras it carries. Instances are immutable.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class ModelSettings
    {
        private final long contextLimitTokens;

        private final long maxOutputTokens;

        private final double temperature;

        private final long chunkTokenSize;

        private final long wholeDocumentTokenLimit;

        private final String developer;

        private final Map<String, Object> extra;

        /**
         * Create a model settings snapshot.
         *
         * @param contextLimitTokens the maximum context window, in tokens
         * @param maxOutputTokens the maximum number of tokens to generate in the response
         * @param temperature the sampling temperature
         * @param chunkTokenSize the number of input tokens to send per chunk when the input exceeds the context
         *            window
         * @param wholeDocumentTokenLimit the whole-document token limit
         * @param developer the organization that developed the model, or {@code null} if not set
         * @param extra format-specific extras with no dedicated field of their own, or {@code null} for none
         */
        public ModelSettings(final long contextLimitTokens, final long maxOutputTokens, final double temperature,
            final long chunkTokenSize, final long wholeDocumentTokenLimit, @Nullable final String developer,
            @Nullable final Map<String, Object> extra)
        {
            this.contextLimitTokens = contextLimitTokens;
            this.maxOutputTokens = maxOutputTokens;
            this.temperature = temperature;
            this.chunkTokenSize = chunkTokenSize;
            this.wholeDocumentTokenLimit = wholeDocumentTokenLimit;
            this.developer = developer;
            this.extra = extra == null ? Collections.emptyMap() : new HashMap<>(extra);
        }

        /**
         * The maximum context window of this model, in tokens.
         *
         * @return the context limit in tokens
         */
        public long getContextLimitTokens()
        {
            return this.contextLimitTokens;
        }

        /**
         * The maximum number of tokens to generate in the response.
         *
         * @return the maximum output tokens
         */
        public long getMaxOutputTokens()
        {
            return this.maxOutputTokens;
        }

        /**
         * The sampling temperature.
         *
         * @return the temperature
         */
        public double getTemperature()
        {
            return this.temperature;
        }

        /**
         * The number of input tokens to send per chunk when the input exceeds the context window.
         *
         * @return the chunk token size
         */
        public long getChunkTokenSize()
        {
            return this.chunkTokenSize;
        }

        /**
         * The document-size threshold, in estimated tokens, below which an uploaded document is sent to the model
         * whole rather than chunked.
         *
         * @return the whole-document token limit
         */
        public long getWholeDocumentTokenLimit()
        {
            return this.wholeDocumentTokenLimit;
        }

        /**
         * The organization that developed this model.
         *
         * @return the developer name, or {@code null} if not set
         */
        @Nullable
        public String getDeveloper()
        {
            return this.developer;
        }

        /**
         * Read an arbitrary, format-specific property that has no dedicated field of its own.
         *
         * @param name the property name
         * @return the property value as a string, or {@code null} if not set
         */
        @Nullable
        public String getProperty(@NotNull final String name)
        {
            final Object value = this.extra.get(name);
            return value == null ? null : value.toString();
        }

        @Override
        public boolean equals(final Object other)
        {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ModelSettings)) {
                return false;
            }
            final ModelSettings that = (ModelSettings) other;
            return this.contextLimitTokens == that.contextLimitTokens
                && this.maxOutputTokens == that.maxOutputTokens
                && Double.compare(this.temperature, that.temperature) == 0
                && this.chunkTokenSize == that.chunkTokenSize
                && this.wholeDocumentTokenLimit == that.wholeDocumentTokenLimit
                && Objects.equals(this.developer, that.developer) && this.extra.equals(that.extra);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(this.contextLimitTokens, this.maxOutputTokens, this.temperature,
                this.chunkTokenSize, this.wholeDocumentTokenLimit, this.developer, this.extra);
        }
    }
}
