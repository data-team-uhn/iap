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
package io.uhndata.iap.tags.internal;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.tags.models.TagDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TagListServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TagListServletTest
{
    private final SlingContext context = new SlingContext();

    private TagListServlet servlet;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.addModelsForClasses(Content.class, TagDefinition.class);
        this.context.create().resource("/Tags",
            "sling:resourceType", "iap/TagsHomepage");
        this.context.create().resource("/Tags/draft", Map.of(
            "sling:resourceType", "iap/TagDefinition",
            "label", "Draft",
            "description", "Work in progress",
            "category", new String[] { "lifecycle" },
            "color", "#9e9e9e",
            "order", 1L));
        this.context.create().resource("/Tags/submitted", Map.of(
            "sling:resourceType", "iap/TagDefinition",
            "category", new String[] { "lifecycle" },
            "system", true,
            "order", 2L));
        this.context.create().resource("/Tags/sensitive", Map.of(
            "sling:resourceType", "iap/TagDefinition",
            "category", new String[] { "privacy" },
            "inheritable", true,
            "targetResources", new String[] { "iap/Entity" },
            "order", 3L));

        this.servlet = new TagListServlet();
        final Field reference = TagListServlet.class.getDeclaredField("tagManager");
        reference.setAccessible(true);
        reference.set(this.servlet, new TagManagerImpl());
    }

    @Test
    void listsAllTags() throws Exception
    {
        final JsonObject result = get(Map.of());

        assertEquals(3, result.getInt("total"));
        assertEquals(List.of("draft", "submitted", "sensitive"),
            result.getJsonArray("tags").stream()
                .map(tag -> ((JsonObject) tag).getString("name")).toList());
    }

    @Test
    void serializesTheFullDefinition() throws Exception
    {
        final JsonObject draft = get(Map.of()).getJsonArray("tags").getJsonObject(0);

        assertEquals("draft", draft.getString("name"));
        assertEquals("Draft", draft.getString("label"));
        assertEquals("Work in progress", draft.getString("description"));
        assertEquals(1, draft.getJsonArray("category").size());
        assertEquals("lifecycle", draft.getJsonArray("category").getString(0));
        assertFalse(draft.getBoolean("inheritable"));
        assertFalse(draft.getBoolean("aggregated"));
        assertTrue(draft.getJsonArray("targetResources").isEmpty());
        assertEquals("#9e9e9e", draft.getString("color"));
        assertEquals(1, draft.getInt("order"));
        assertFalse(draft.getBoolean("system"));
        assertEquals("/Tags/draft", draft.getString("path"));
        // Unset optional properties are simply absent, not nulls
        final JsonObject submitted = get(Map.of()).getJsonArray("tags").getJsonObject(1);
        assertFalse(submitted.containsKey("description"));
        assertFalse(submitted.containsKey("color"));
        assertTrue(submitted.getBoolean("system"));
    }

    @Test
    void filtersByCategory() throws Exception
    {
        final JsonObject result = get(Map.of("category", "LIFECYCLE"));

        assertEquals(2, result.getInt("total"));
        assertEquals(List.of("draft", "submitted"),
            result.getJsonArray("tags").stream()
                .map(tag -> ((JsonObject) tag).getString("name")).toList());
    }

    @Test
    void filtersByQueryText() throws Exception
    {
        final JsonObject result = get(Map.of("query", "progress"));

        assertEquals(1, result.getInt("total"));
        assertEquals("draft", result.getJsonArray("tags").getJsonObject(0).getString("name"));
    }

    @Test
    void filtersByTargetResource() throws Exception
    {
        this.context.create().resource("/data/part",
            "sling:resourceType", "iap/EntityPart");
        final JsonObject result = get(Map.of("target", "/data/part"));

        // The entity-only "sensitive" tag may not be placed on an entity part
        assertEquals(List.of("draft", "submitted"),
            result.getJsonArray("tags").stream()
                .map(tag -> ((JsonObject) tag).getString("name")).toList());
    }

    @Test
    void reportsInaccessibleTargetResource() throws Exception
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource("/Tags"));
        request.setParameterMap(Map.of("target", "/data/missing"));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.service(request, response);

        assertEquals(400, response.getStatus());
        final JsonObject result = Json.createReader(new StringReader(response.getOutputAsString())).readObject();
        assertEquals("error", result.getString("status"));
    }

    private JsonObject get(final Map<String, Object> parameters) throws Exception
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource("/Tags"));
        request.setParameterMap(parameters);
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.service(request, response);

        assertEquals(200, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        return Json.createReader(new StringReader(response.getOutputAsString())).readObject();
    }
}
