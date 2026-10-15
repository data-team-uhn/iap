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
package io.uhndata.iap.conditions.models;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.autodoc.api.AutoDocumentable;
import io.uhndata.iap.conditions.api.ConditionTypes;
import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.internal.ConditionTypesImpl;
import io.uhndata.iap.conditions.spi.Candidate;
import io.uhndata.iap.conditions.spi.CandidateProvider;
import io.uhndata.iap.conditions.spi.ConditionComparator;
import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.content.models.Content;

import static org.apache.sling.api.resource.ResourceResolver.PROPERTY_RESOURCE_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConditionTypesHomepage}, read as the autodoc servlet reads it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ConditionTypesHomepageTest
{
    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        final ConditionTypesImpl types = new ConditionTypesImpl();
        inject(types, "sources", List.of(source("answer", "The answer to a question"), source("tags", "The tags")));
        inject(types, "providers", List.of(new CandidateProvider()
        {
            @Override
            public String getSource()
            {
                return "answer";
            }

            @Override
            public List<Candidate> getCandidates(final Resource condition)
            {
                return List.of();
            }
        }));
        this.context.registerService(ConditionTypes.class, types);
        this.context.addModelsForClasses(Content.class, ConditionTypesHomepage.class);
    }

    @Test
    void documentsEveryComparatorAggregatorAndSource()
    {
        final JsonObject items = catalogue("Conditions", "What a condition can compare, and how.")
            .toDocumentationJson().getJsonObject("items");
        assertEquals("equals", items.getJsonArray(ConditionComparator.CATEGORY).getJsonObject(0).getString("name"));
        assertEquals("count", items.getJsonArray("Aggregators").getJsonObject(0).getString("name"));
        assertEquals(2, items.getJsonArray(OperandResolver.CATEGORY).size());
    }

    @Test
    void saysWhereASourcesCandidatesCanBeAskedFor()
    {
        final List<JsonObject> sources = catalogue("Conditions", null).toDocumentationJson()
            .getJsonObject("items").getJsonArray(OperandResolver.CATEGORY).getValuesAs(JsonObject.class);
        assertEquals("{+path}.conditionCandidates.json?source=answer", sources.get(0).getString("candidates"));
        assertEquals("The answer to a question", sources.get(0).getString("label"));
        // Nothing lists the candidates of the second one
        assertFalse(sources.get(1).containsKey("candidates"));
    }

    @Test
    void takesItsHeadingFromItsProperties()
    {
        final AutoDocumentable documented = catalogue("Conditions", "What a condition can compare, and how.");
        assertEquals("Conditions", documented.getDocumentationTitle());
        assertEquals("What a condition can compare, and how.", documented.getDocumentationIntro());
        assertTrue(documented.toMarkdown().contains("### The tags (`tags`)"));
    }

    @Test
    void fallsBackToAPlainHeading()
    {
        final AutoDocumentable documented = catalogue(null, null);
        assertEquals("Conditions", documented.getDocumentationTitle());
        assertNull(documented.getDocumentationIntro());
    }

    private AutoDocumentable catalogue(final String title, final String description)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(PROPERTY_RESOURCE_TYPE, ConditionTypesHomepage.RESOURCE_TYPE);
        if (title != null) {
            properties.put("title", title);
        }
        if (description != null) {
            properties.put("description", description);
        }
        return this.context.create().resource("/ConditionTypes", properties).adaptTo(AutoDocumentable.class);
    }

    private static OperandResolver source(final String name, final String label)
    {
        return new OperandResolver()
        {
            @Override
            public String getSource()
            {
                return name;
            }

            @Override
            public String getDocumentationLabel()
            {
                return label;
            }

            @Override
            public String getDescription()
            {
                return label + ".";
            }

            @Override
            public Operand resolve(final ConditionOperand operand,
                final Content condition)
            {
                return Operand.EMPTY;
            }
        };
    }

    private static void inject(final ConditionTypesImpl types, final String field, final Object value)
        throws ReflectiveOperationException
    {
        final Field declared = ConditionTypesImpl.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(types, value);
    }
}
