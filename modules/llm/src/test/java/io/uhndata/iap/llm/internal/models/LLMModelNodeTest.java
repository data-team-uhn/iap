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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link LLMModelNode}: that it reads an {@code llm:Model} node's known properties and applies
 * the CND defaults when they are absent.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LLMModelNodeTest
{
    private static final String PATH = "/libs/iap/config/LLM/prompter/GPT-OSS-120B";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.addModelsForClasses(LLMModelNode.class);
        NodeTypeDefinitionScanner.get().register(this.context.resourceResolver().adaptTo(Session.class),
            List.of("SLING-INF/nodetypes/llms.cnd"), ResourceResolverType.JCR_OAK.getNodeTypeMode());
    }

    /**
     * Create the node as an {@code llm:Model} and adapt it.
     *
     * <p>
     * The primary type is the point. The resource type this model binds to is autocreated by the node
     * type, so a fixture that writes it by hand tests the fixture.
     * </p>
     *
     * @param properties the properties to set beyond the primary type
     * @return the adapted model
     */
    private LLMModelNode adapt(final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put("jcr:primaryType", "llm:Model");
        final Resource resource = this.context.create().resource(PATH, all);
        return resource.adaptTo(LLMModelNode.class);
    }

    @Test
    void takesItsResourceTypeFromTheNodeType()
    {
        final Resource resource = this.context.create().resource(PATH, Map.of("jcr:primaryType", "llm:Model"));

        assertEquals(LLMModelNode.RESOURCE_TYPE, resource.getValueMap().get("sling:resourceType", String.class),
            "the servlet binding and this model both rest on the autocreated resource type");
    }

    @Test
    void readsTheKnownProperties()
    {
        final LLMModelNode node = adapt(Map.of(
            "contextLimitTokens", 131072L,
            "temperature", 0.7d,
            "developer", "openai"));

        assertEquals(131072, node.getContextLimitTokens());
        assertEquals(0.7d, node.getTemperature());
        assertEquals("openai", node.getDeveloper());
    }

    @Test
    void appliesTheDocumentedDefaultsWhenAPropertyIsMissing()
    {
        final LLMModelNode node = adapt(Map.of());

        assertEquals(0, node.getContextLimitTokens());
        assertEquals(0.0d, node.getTemperature());
        assertNull(node.getDeveloper());
    }
}
