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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.llm.LLMSettings.ModelSettings;
import io.uhndata.iap.llm.LLMSettings.ProviderSettings;
import io.uhndata.iap.llm.internal.models.LLMModelNode;
import io.uhndata.iap.llm.internal.models.LLMProviderNode;

/**
 * Default {@link LLMConfigurationService} that reads the active selection from {@link #SELECTION_PATH} and the
 * catalog of providers and models it selects from from {@link #CATALOG_PATH}, using a dedicated read-only
 * service user.
 *
 * <p>
 * The two live under different roots because they change on a different schedule. {@link #CATALOG_PATH} is
 * seeded from initial content and overwritten on every deploy, so shipping a new provider or model reaches a
 * running instance without a manual step. {@link #SELECTION_PATH} is seeded once and never overwritten after
 * that, since it holds the choice an administrator made at runtime, which a redeploy must not silently reset.
 * </p>
 *
 * <p>
 * The provider and model nodes are read through {@link LLMProviderNode} and {@link LLMModelNode} — Sling
 * Models that give the known, typed properties a name instead of another hand-rolled pass over a raw
 * {@code ValueMap} — and their fields are copied into the {@link LLMSettings} snapshot this service returns.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = LLMConfigurationService.class)
public class LLMConfigurationServiceImpl implements LLMConfigurationService
{
    /** The JCR path of the node holding the active provider/model selection. */
    public static final String SELECTION_PATH = "/apps/iap/config/LLM";

    /** The JCR path of the node holding the catalog of providers and models the selection is made from. */
    public static final String CATALOG_PATH = "/libs/iap/config/LLM";

    private static final String SUBSERVICE = "llmConfig";

    private static final String ACTIVE_PROVIDER = "activeProvider";

    private static final String ACTIVE_MODEL = "activeModel";

    /** Provider properties with a dedicated {@link LLMProviderNode} field, left out of {@code extra}. */
    private static final Set<String> KNOWN_PROVIDER_PROPERTIES =
        Set.of("endpoint", "apiKeyEnvVar", "timeoutSeconds");

    /** Model properties with a dedicated {@link LLMModelNode} field, left out of {@code extra}. */
    private static final Set<String> KNOWN_MODEL_PROPERTIES = Set.of("contextLimitTokens", "maxOutputTokens",
        "temperature", "chunkTokenSize", "wholeDocumentTokenLimit", "developer");

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    public LLMSettings getActiveSettings() throws IOException
    {
        try (ResourceResolver resolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            final Resource selection = resolver.getResource(SELECTION_PATH);
            if (selection == null) {
                throw new IOException("LLM configuration not found at " + SELECTION_PATH);
            }
            final ValueMap configProps = selection.getValueMap();
            final String providerName = configProps.get(ACTIVE_PROVIDER, String.class);
            final String modelName = configProps.get(ACTIVE_MODEL, String.class);
            if (providerName == null || providerName.isBlank() || modelName == null || modelName.isBlank()) {
                throw new IOException("No active LLM provider/model is selected in " + SELECTION_PATH);
            }
            final Resource catalog = resolver.getResource(CATALOG_PATH);
            if (catalog == null) {
                throw new IOException("LLM catalog not found at " + CATALOG_PATH);
            }
            final Resource provider = catalog.getChild(providerName);
            if (provider == null) {
                throw new IOException("Active LLM provider '" + providerName + "' does not exist");
            }
            final Resource model = provider.getChild(modelName);
            if (model == null) {
                throw new IOException("Active LLM model '" + modelName + "' does not exist under provider '"
                    + providerName + "'");
            }
            return new LLMSettings(providerName, providerSettings(provider), modelName, modelSettings(model));
        } catch (LoginException e) {
            throw new IOException("Could not access the LLM configuration", e);
        }
    }

    private static ProviderSettings providerSettings(final Resource provider) throws IOException
    {
        final LLMProviderNode node = provider.adaptTo(LLMProviderNode.class);
        if (node == null) {
            throw new IOException("Could not read the LLM provider at " + provider.getPath());
        }
        return new ProviderSettings(node.getEndpoint(), node.getApiKeyEnvVar(), node.getTimeoutSeconds(),
            extra(provider.getValueMap(), KNOWN_PROVIDER_PROPERTIES));
    }

    private static ModelSettings modelSettings(final Resource model) throws IOException
    {
        final LLMModelNode node = model.adaptTo(LLMModelNode.class);
        if (node == null) {
            throw new IOException("Could not read the LLM model at " + model.getPath());
        }
        return new ModelSettings(node.getContextLimitTokens(), node.getMaxOutputTokens(), node.getTemperature(),
            node.getChunkTokenSize(), node.getWholeDocumentTokenLimit(), node.getDeveloper(),
            extra(model.getValueMap(), KNOWN_MODEL_PROPERTIES));
    }

    /**
     * The properties of a node that are neither JCR/Sling bookkeeping nor already exposed through a dedicated
     * field, i.e. exactly what {@link LLMSettings#getProviderProperty} / {@code getModelProperty} can still
     * answer.
     *
     * @param valueMap the node's properties
     * @param known the property names already covered by a dedicated field
     * @return the remaining properties, by name
     */
    private static Map<String, Object> extra(final ValueMap valueMap, final Set<String> known)
    {
        final Map<String, Object> result = new HashMap<>();
        for (final Map.Entry<String, Object> entry : valueMap.entrySet()) {
            final String key = entry.getKey();
            if (!key.startsWith("jcr:") && !key.startsWith("sling:") && !known.contains(key)) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }
}
