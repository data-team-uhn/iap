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
 * Unit tests for {@link LLMModelNode}: that it reads an {@code llm:Model} node's known properties, applies the
 * CND defaults when they are absent, and still answers for everything else through
 * {@link LLMModelNode#getProperty}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LLMModelNodeTest
{
    private static final String PATH = "/libs/iap/config/LLM/prompter/GPT-OSS-120B";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(LLMModelNode.class);
    }

    private LLMModelNode adapt(final Map<String, Object> properties)
    {
        final Resource resource = this.context.create().resource(PATH, properties);
        return resource.adaptTo(LLMModelNode.class);
    }

    @Test
    void readsTheKnownProperties()
    {
        final LLMModelNode node = adapt(Map.of(
            "contextLimitTokens", 131072L,
            "maxOutputTokens", 2000L,
            "temperature", 0.7d,
            "chunkTokenSize", 30000L,
            "wholeDocumentTokenLimit", 15000L,
            "developer", "openai"));

        assertEquals(131072, node.getContextLimitTokens());
        assertEquals(2000, node.getMaxOutputTokens());
        assertEquals(0.7d, node.getTemperature());
        assertEquals(30000, node.getChunkTokenSize());
        assertEquals(15000, node.getWholeDocumentTokenLimit());
        assertEquals("openai", node.getDeveloper());
    }

    @Test
    void appliesTheDocumentedDefaultsWhenAPropertyIsMissing()
    {
        final LLMModelNode node = adapt(Map.of());

        assertEquals(0, node.getContextLimitTokens());
        assertEquals(2000, node.getMaxOutputTokens());
        assertEquals(0.0d, node.getTemperature());
        assertEquals(0, node.getChunkTokenSize());
        assertEquals(20000, node.getWholeDocumentTokenLimit());
        assertNull(node.getDeveloper());
    }

    @Test
    void readsAPropertyWithNoDedicatedField()
    {
        final LLMModelNode node = adapt(Map.of("tuned", true));

        assertEquals("true", node.getProperty("tuned"));
        assertNull(node.getProperty("absent"));
    }
}
