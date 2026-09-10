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
package io.uhndata.iap.llm.internal.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Default;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A Sling Model wrapping an {@code llm:Model} node: the generation settings for one model offered by a
 * provider (token limits, temperature, chunking thresholds). Read by
 * {@link io.uhndata.iap.llm.internal.LLMConfigurationServiceImpl}, which copies the fields it needs into an
 * {@link io.uhndata.iap.llm.LLMSettings.ModelSettings} snapshot — this class exists only to give that read a
 * name and a type instead of another pass over a raw {@code ValueMap}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = LLMModelNode.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LLMModelNode
{
    /** The {@code sling:resourceType} of an {@code llm:Model} node. */
    public static final String RESOURCE_TYPE = "llm/Model";

    /** Matches the CND default ({@code llms.cnd}); keep the two in step. */
    private static final long DEFAULT_MAX_OUTPUT_TOKENS = 2000;

    /**
     * Matches the historic chunker {@code min_structure_tokens} default so CLI-only runs stay aligned with
     * configured models; also matches the CND default ({@code llms.cnd}).
     */
    private static final long DEFAULT_WHOLE_DOCUMENT_TOKEN_LIMIT = 20000;

    @ValueMapValue
    private long contextLimitTokens;

    @ValueMapValue
    @Default(longValues = DEFAULT_MAX_OUTPUT_TOKENS)
    private long maxOutputTokens;

    @ValueMapValue
    @Default(doubleValues = 0.0)
    private double temperature;

    @ValueMapValue
    private long chunkTokenSize;

    @ValueMapValue
    @Default(longValues = DEFAULT_WHOLE_DOCUMENT_TOKEN_LIMIT)
    private long wholeDocumentTokenLimit;

    @ValueMapValue
    private String developer;

    @SlingObject
    private Resource resource;

    /**
     * The maximum context window of this model, in tokens.
     *
     * @return the context limit in tokens, or 0 if not set
     */
    public long getContextLimitTokens()
    {
        return this.contextLimitTokens;
    }

    /**
     * The maximum number of tokens to generate in the response.
     *
     * @return the maximum output tokens, defaulting to 2000 when not set
     */
    public long getMaxOutputTokens()
    {
        return this.maxOutputTokens;
    }

    /**
     * The sampling temperature.
     *
     * @return the temperature, defaulting to 0.0 when not set
     */
    public double getTemperature()
    {
        return this.temperature;
    }

    /**
     * The number of input tokens to send per chunk when the input exceeds the context window.
     *
     * @return the chunk token size, or 0 if not set
     */
    public long getChunkTokenSize()
    {
        return this.chunkTokenSize;
    }

    /**
     * The document-size threshold, in estimated tokens, below which an uploaded document is sent to the model
     * whole rather than chunked.
     *
     * @return the whole-document token limit, defaulting to 20000 when not set
     */
    public long getWholeDocumentTokenLimit()
    {
        return this.wholeDocumentTokenLimit;
    }

    /**
     * The organization that developed this model (e.g. {@code google}, {@code anthropic}, {@code alibaba}).
     *
     * @return the developer name, or {@code null} if not set
     */
    @Nullable
    public String getDeveloper()
    {
        return this.developer;
    }

    /**
     * Read an arbitrary, format-specific property of this model that has no dedicated field of its own.
     *
     * @param name the property name
     * @return the property value as a string, or {@code null} if not set
     */
    @Nullable
    public String getProperty(@NotNull final String name)
    {
        final Object value = this.resource.getValueMap().get(name);
        return value == null ? null : value.toString();
    }
}
