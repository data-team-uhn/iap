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

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMSettings;

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
            return new LLMSettings(providerName, toMap(provider.getValueMap()),
                modelName, toMap(model.getValueMap()));
        } catch (LoginException e) {
            throw new IOException("Could not access the LLM configuration", e);
        }
    }

    private static Map<String, Object> toMap(final ValueMap valueMap)
    {
        final Map<String, Object> result = new HashMap<>();
        for (final Map.Entry<String, Object> entry : valueMap.entrySet()) {
            final String key = entry.getKey();
            if (!key.startsWith("jcr:") && !key.startsWith("sling:")) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }
}
