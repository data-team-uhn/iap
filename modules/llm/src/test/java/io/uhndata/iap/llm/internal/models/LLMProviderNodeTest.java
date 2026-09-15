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

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link LLMProviderNode}: that it reads an {@code llm:Provider} node's known properties,
 * applies the CND defaults when they are absent, and still answers for everything else through
 * {@link LLMProviderNode#getProperty}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LLMProviderNodeTest
{
    private static final String PATH = "/libs/iap/config/LLM/prompter";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(LLMProviderNode.class);
    }

    private LLMProviderNode adapt(final Map<String, Object> properties)
    {
        final Resource resource = this.context.create().resource(PATH, properties);
        return resource.adaptTo(LLMProviderNode.class);
    }

    @Test
    void readsTheKnownProperties()
    {
        final LLMProviderNode node = adapt(Map.of(
            "endpoint", "https://example.invalid/v1",
            "apiKeyEnvVar", "PROMPTER_API_KEY",
            "timeoutSeconds", 90L));

        assertEquals("https://example.invalid/v1", node.getEndpoint());
        assertEquals("PROMPTER_API_KEY", node.getApiKeyEnvVar());
        assertEquals(90, node.getTimeoutSeconds());
    }

    @Test
    void appliesTheCndDefaultWhenTimeoutSecondsIsAbsent()
    {
        final LLMProviderNode node = adapt(Map.of());

        assertNull(node.getEndpoint());
        assertNull(node.getApiKeyEnvVar());
        assertEquals(120, node.getTimeoutSeconds());
    }

    @Test
    void readsAPropertyWithNoDedicatedField()
    {
        final LLMProviderNode node = adapt(Map.of("projectId", "some-project"));

        assertEquals("some-project", node.getProperty("projectId"));
        assertNull(node.getProperty("absent"));
    }

    @Test
    void readsAPropertyThroughToString()
    {
        final LLMProviderNode node = adapt(Map.of("apiVersion", 3L));

        assertEquals("3", node.getProperty("apiVersion"));
    }
}
