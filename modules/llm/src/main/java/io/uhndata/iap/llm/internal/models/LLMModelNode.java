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
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.Nullable;

/**
 * A Sling Model wrapping an {@code llm:Model} node: the generation settings for one model offered by a
 * provider (token limits, temperature). Read by
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

    @ValueMapValue
    private long contextLimitTokens;

    @ValueMapValue
    @Default(doubleValues = 0.0)
    private double temperature;

    @ValueMapValue
    private String developer;

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
     * The sampling temperature.
     *
     * @return the temperature, defaulting to 0.0 when not set
     */
    public double getTemperature()
    {
        return this.temperature;
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
}
