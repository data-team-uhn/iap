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
package io.uhndata.iap.categories.internal;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.categories.models.CategoriesHomepage;
import io.uhndata.iap.categories.models.Category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CategoryDescriptionsServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CategoryDescriptionsServletTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String LEAF_UUID = "11111111-2222-3333-4444-555555555555";

    private final SlingContext context = new SlingContext();

    private CategoryDescriptionsServlet servlet;

    private SlingJakartaHttpServletRequest request;

    private SlingJakartaHttpServletResponse response;

    private StringWriter output;

    @BeforeEach
    void setUp() throws Exception
    {
        this.servlet = new CategoryDescriptionsServlet();
        this.request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        this.response = Mockito.mock(SlingJakartaHttpServletResponse.class);
        this.output = new StringWriter();
        Mockito.when(this.response.getWriter()).thenReturn(new PrintWriter(this.output));
    }

    /**
     * Builds a tree exercising every serving rule: a branch category whose leaves are served, a retired leaf, a
     * retired branch with a live leaf under it (pruned entirely), and a non-category child (ignored).
     */
    private void createTree()
    {
        this.context.create().resource("/Categories", SLING_RESOURCE_TYPE, CategoriesHomepage.RESOURCE_TYPE);
        this.context.create().resource("/Categories/Retrospective", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Retrospective studies",
            "description", "Studies using existing data or specimens."));
        this.context.create().resource("/Categories/Retrospective/RetrospectiveData", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Retrospective Data Studies",
            "description", "Chart reviews and analyses of previously collected data.",
            "jcr:uuid", LEAF_UUID));
        // No label, no description, no uuid: served with the node name as label, without description and id
        this.context.create().resource("/Categories/Retrospective/RetrospectiveBiospecimen",
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE);
        // A non-category child does not stop its parent from being a leaf
        this.context.create().resource("/Categories/Retrospective/RetrospectiveBiospecimen/attachment",
            SLING_RESOURCE_TYPE, "iap/Content");
        this.context.create().resource("/Categories/Paper", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Paper submissions",
            "retired", true));
        this.context.create().resource("/Categories/Legacy", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Legacy studies",
            "retired", true));
        this.context.create().resource("/Categories/Legacy/LegacyData", Map.of(
            SLING_RESOURCE_TYPE, Category.RESOURCE_TYPE,
            "label", "Legacy Data Studies"));
        this.context.create().resource("/Categories/notes", SLING_RESOURCE_TYPE, "iap/Content");
        Mockito.when(this.request.getResource())
            .thenReturn(this.context.resourceResolver().getResource("/Categories"));
    }

    private JsonArray getResponseJson()
    {
        return Json.createReader(new StringReader(this.output.toString())).readArray();
    }

    @Test
    void servesOnlyLiveLeafCategories() throws Exception
    {
        createTree();
        this.servlet.doGet(this.request, this.response);

        final JsonArray results = getResponseJson();
        assertEquals(2, results.size());
        assertEquals("RetrospectiveData", results.getJsonObject(0).getString("name"));
        assertEquals("RetrospectiveBiospecimen", results.getJsonObject(1).getString("name"));
    }

    @Test
    void servesTheCategoryDetails() throws Exception
    {
        createTree();
        this.servlet.doGet(this.request, this.response);

        final JsonObject leaf = getResponseJson().getJsonObject(0);
        assertEquals("RetrospectiveData", leaf.getString("name"));
        assertEquals("Retrospective Data Studies", leaf.getString("label"));
        assertEquals("Chart reviews and analyses of previously collected data.", leaf.getString("description"));
        assertEquals("/Categories/Retrospective/RetrospectiveData", leaf.getString("path"));
        assertEquals(LEAF_UUID, leaf.getString("id"));
    }

    @Test
    void omitsMissingOptionalFieldsAndFallsBackToTheNameAsLabel() throws Exception
    {
        createTree();
        this.servlet.doGet(this.request, this.response);

        final JsonObject leaf = getResponseJson().getJsonObject(1);
        assertEquals("RetrospectiveBiospecimen", leaf.getString("label"));
        assertFalse(leaf.containsKey("description"));
        assertFalse(leaf.containsKey("id"));
    }

    @Test
    void servesAnEmptyArrayForAnEmptyTree() throws Exception
    {
        this.context.create().resource("/Categories", SLING_RESOURCE_TYPE, CategoriesHomepage.RESOURCE_TYPE);
        Mockito.when(this.request.getResource())
            .thenReturn(this.context.resourceResolver().getResource("/Categories"));
        this.servlet.doGet(this.request, this.response);

        assertTrue(getResponseJson().isEmpty());
    }

    @Test
    void setsTheResponseContentType() throws Exception
    {
        createTree();
        this.servlet.doGet(this.request, this.response);

        Mockito.verify(this.response).setContentType("application/json");
        Mockito.verify(this.response).setCharacterEncoding("UTF-8");
    }
}
