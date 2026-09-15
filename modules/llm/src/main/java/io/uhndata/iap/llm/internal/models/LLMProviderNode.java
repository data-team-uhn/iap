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
 * A Sling Model wrapping an {@code llm:Provider} node: the connection-level settings for one LLM provider
 * (endpoint, credentials, timeout), plus whatever format-specific extras it carries (such as {@code projectId}
 * for Prompter). Read by {@link io.uhndata.iap.llm.internal.LLMConfigurationServiceImpl}, which copies the
 * fields it needs into an {@link io.uhndata.iap.llm.LLMSettings.ProviderSettings} snapshot — this class exists
 * only to give that read a name and a type instead of another pass over a raw {@code ValueMap}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = LLMProviderNode.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LLMProviderNode
{
    /** The {@code sling:resourceType} of an {@code llm:Provider} node. */
    public static final String RESOURCE_TYPE = "llm/Provider";

    /** Matches the CND default ({@code llms.cnd}); keep the two in step. */
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;

    @ValueMapValue
    private String endpoint;

    @ValueMapValue
    private String apiKeyEnvVar;

    @ValueMapValue
    @Default(longValues = DEFAULT_TIMEOUT_SECONDS)
    private long timeoutSeconds;

    @SlingObject
    private Resource resource;

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
     * @return the timeout in seconds, defaulting to 120 when not set
     */
    public long getTimeoutSeconds()
    {
        return this.timeoutSeconds;
    }

    /**
     * Read an arbitrary, format-specific property of this provider (such as {@code projectId} or
     * {@code apiVersion}) that has no dedicated field of its own.
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
