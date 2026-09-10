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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.llm.internal.models.LLMModelNode;
import io.uhndata.iap.llm.internal.models.LLMProviderNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LLMConfigurationServiceImpl}, covering the resolution of the active provider and
 * model and every way that resolution can fail.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LLMConfigurationServiceImplTest
{
    private static final String SELECTION_PATH = "/apps/iap/config/LLM";

    private static final String CATALOG_PATH = "/libs/iap/config/LLM";

    private static final String ACTIVE_PROVIDER = "activeProvider";

    private static final String ACTIVE_MODEL = "activeModel";

    private static final String PROVIDER = "local";

    private static final String MODEL = "llama3.2-3b";

    private final SlingContext context = new SlingContext();

    private LLMConfigurationServiceImpl service;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.addModelsForClasses(LLMProviderNode.class, LLMModelNode.class);
        this.service = new LLMConfigurationServiceImpl();
        inject(this.service, new TestResolverFactory(this.context.resourceResolver()));
    }

    private static void inject(final Object target, final ResourceResolverFactory factory) throws Exception
    {
        final Field field = target.getClass().getDeclaredField("resolverFactory");
        field.setAccessible(true);
        field.set(target, factory);
    }

    private void createConfiguration(final String activeProvider, final String activeModel)
    {
        final Map<String, Object> configProperties = new HashMap<>();
        configProperties.put("jcr:primaryType", "nt:unstructured");
        configProperties.put("title", "LLM Configuration");
        // a null value stands for the property being absent altogether
        if (activeProvider != null) {
            configProperties.put(ACTIVE_PROVIDER, activeProvider);
        }
        if (activeModel != null) {
            configProperties.put(ACTIVE_MODEL, activeModel);
        }
        this.context.create().resource(SELECTION_PATH, configProperties);
        this.context.create().resource(CATALOG_PATH, Map.of("jcr:primaryType", "nt:unstructured"));
        this.context.create().resource(CATALOG_PATH + "/" + PROVIDER, Map.of(
            "sling:resourceType", "llm/Provider",
            "label", "Local (Ollama)",
            "api", "openai",
            "endpoint", "http://localhost:11434/v1",
            "timeoutSeconds", 600L));
        this.context.create().resource(CATALOG_PATH + "/" + PROVIDER + "/" + MODEL, Map.of(
            "sling:resourceType", "llm/Model",
            "maxOutputTokens", 1024L,
            "temperature", 0.0d,
            "developer", "meta"));
    }

    @Test
    void resolvesTheActiveProviderAndModel() throws IOException
    {
        createConfiguration(PROVIDER, MODEL);

        final LLMSettings settings = this.service.getActiveSettings();

        assertEquals(PROVIDER, settings.getProviderName());
        assertEquals(MODEL, settings.getModelName());
        assertEquals("http://localhost:11434/v1", settings.getEndpoint());
        assertEquals(600, settings.getTimeoutSeconds());
        assertEquals("openai", settings.getProviderProperty("api"));
        assertEquals(1024, settings.getMaxOutputTokens());
        assertEquals("meta", settings.getDeveloper());
    }

    @Test
    void leavesOutTheJcrAndSlingBookkeepingProperties() throws IOException
    {
        createConfiguration(PROVIDER, MODEL);

        final LLMSettings settings = this.service.getActiveSettings();

        assertNull(settings.getProviderProperty("jcr:primaryType"));
        assertNull(settings.getProviderProperty("sling:resourceType"));
        assertNull(settings.getModelProperty("jcr:primaryType"));
    }

    @Test
    void failsWhenThereIsNoConfigurationNode()
    {
        final IOException failure = assertThrows(IOException.class, () -> this.service.getActiveSettings());
        assertTrue(failure.getMessage().contains(SELECTION_PATH));
    }

    @Test
    void failsWhenThereIsNoCatalogNode()
    {
        this.context.create().resource(SELECTION_PATH, Map.of(
            ACTIVE_PROVIDER, PROVIDER,
            ACTIVE_MODEL, MODEL));

        final IOException failure = assertThrows(IOException.class, () -> this.service.getActiveSettings());
        assertTrue(failure.getMessage().contains(CATALOG_PATH));
    }

    @Test
    void failsWhenNoProviderIsSelected()
    {
        createConfiguration("  ", MODEL);

        final IOException failure = assertThrows(IOException.class, () -> this.service.getActiveSettings());
        assertTrue(failure.getMessage().contains("No active LLM provider/model"));
    }

    @Test
    void failsWhenNoModelIsSelected()
    {
        createConfiguration(PROVIDER, null);

        final IOException failure = assertThrows(IOException.class, () -> this.service.getActiveSettings());
        assertTrue(failure.getMessage().contains("No active LLM provider/model"));
    }

    @Test
    void failsWhenTheSelectedProviderIsNotInTheCatalog()
    {
        createConfiguration("absent", MODEL);

        final IOException failure = assertThrows(IOException.class, () -> this.service.getActiveSettings());
        assertTrue(failure.getMessage().contains("'absent'"));
    }

    @Test
    void failsWhenTheSelectedModelIsNotOfferedByTheProvider()
    {
        createConfiguration(PROVIDER, "absent");

        final IOException failure = assertThrows(IOException.class, () -> this.service.getActiveSettings());
        assertTrue(failure.getMessage().contains("Active LLM model 'absent'"));
    }

    @Test
    void failsWhenTheServiceUserIsMissing() throws Exception
    {
        createConfiguration(PROVIDER, MODEL);
        inject(this.service, new TestResolverFactory(null));

        final IOException failure = assertThrows(IOException.class, () -> this.service.getActiveSettings());
        assertTrue(failure.getMessage().contains("Could not access the LLM configuration"));
    }

    @Test
    void failsWhenTheProviderNodeCannotBeAdapted() throws Exception
    {
        createConfiguration(PROVIDER, MODEL);
        final Resource realProvider = this.context.resourceResolver().getResource(CATALOG_PATH + "/" + PROVIDER);
        final Resource brokenProvider = Mockito.spy(realProvider);
        Mockito.doReturn(null).when(brokenProvider).adaptTo(LLMProviderNode.class);
        final Resource realCatalog = this.context.resourceResolver().getResource(CATALOG_PATH);
        final Resource catalog = Mockito.spy(realCatalog);
        Mockito.doReturn(brokenProvider).when(catalog).getChild(PROVIDER);
        inject(this.service, new TestResolverFactory(resolverReturning(CATALOG_PATH, catalog)));

        final IOException failure = assertThrows(IOException.class, () -> this.service.getActiveSettings());
        assertTrue(failure.getMessage().contains("Could not read the LLM provider"));
    }

    @Test
    void failsWhenTheModelNodeCannotBeAdapted() throws Exception
    {
        createConfiguration(PROVIDER, MODEL);
        final Resource realModel =
            this.context.resourceResolver().getResource(CATALOG_PATH + "/" + PROVIDER + "/" + MODEL);
        final Resource brokenModel = Mockito.spy(realModel);
        Mockito.doReturn(null).when(brokenModel).adaptTo(LLMModelNode.class);
        final Resource realProvider = this.context.resourceResolver().getResource(CATALOG_PATH + "/" + PROVIDER);
        final Resource provider = Mockito.spy(realProvider);
        Mockito.doReturn(brokenModel).when(provider).getChild(MODEL);
        final Resource realCatalog = this.context.resourceResolver().getResource(CATALOG_PATH);
        final Resource catalog = Mockito.spy(realCatalog);
        Mockito.doReturn(provider).when(catalog).getChild(PROVIDER);
        inject(this.service, new TestResolverFactory(resolverReturning(CATALOG_PATH, catalog)));

        final IOException failure = assertThrows(IOException.class, () -> this.service.getActiveSettings());
        assertTrue(failure.getMessage().contains("Could not read the LLM model"));
    }

    /**
     * Wraps the test's own resolver, answering one specific path with a given resource instead of the real
     * one, so a test can substitute a resource that refuses to adapt without disturbing anything else.
     *
     * @param path the path to intercept
     * @param resource the resource to answer with for that path
     * @return a resolver overriding just that one lookup
     */
    private ResourceResolver resolverReturning(final String path, final Resource resource)
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String requestedPath)
            {
                return path.equals(requestedPath) ? resource : super.getResource(requestedPath);
            }
        };
    }

    @Test
    void usesTheDedicatedServiceUser() throws Exception
    {
        createConfiguration(PROVIDER, MODEL);
        final AtomicReference<Map<String, Object>> requested = new AtomicReference<>();
        inject(this.service, new TestResolverFactory(this.context.resourceResolver())
        {
            @Override
            public ResourceResolver getServiceResourceResolver(
                final Map<String, Object> authenticationInfo)
            {
                requested.set(authenticationInfo);
                return LLMConfigurationServiceImplTest.this.context.resourceResolver();
            }
        });

        this.service.getActiveSettings();

        assertEquals("llmConfig", requested.get().get(ResourceResolverFactory.SUBSERVICE));
    }
}
