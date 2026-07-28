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

import java.io.IOException;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.categories.models.CategoriesHomepage;
import io.uhndata.iap.categories.models.Category;

/**
 * Serves the leaf categories that submissions may currently be filed under, as a flat JSON array, at
 * {@code /Categories.descriptions.json}. Each entry holds the category's {@code name} (its node name),
 * {@code label}, {@code description} (omitted when not set), {@code path} and {@code id} (its {@code jcr:uuid},
 * omitted when not available). Retired categories are excluded, together with their whole subtree. The primary
 * consumer is AI-assisted categorization, which builds its prompt from the returned descriptions.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { CategoriesHomepage.RESOURCE_TYPE }, methods = { "GET" },
    selectors = { "descriptions" }, extensions = { "json" })
public class CategoryDescriptionsServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = -2823603627434038268L;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        final JsonArrayBuilder results = Json.createArrayBuilder();
        collectLeaves(request.getResource(), results);
        response.getWriter().print(results.build().toString());
    }

    /**
     * Depth-first traversal of the category tree, adding every live leaf category to the results. A retired
     * category prunes its whole subtree, since submissions may not be filed under any of its descendants either.
     *
     * @param resource the node whose category children are examined, either the homepage or a category
     * @param results the builder collecting the leaf categories
     */
    private void collectLeaves(final Resource resource, final JsonArrayBuilder results)
    {
        for (final Resource child : resource.getChildren()) {
            if (!child.isResourceType(Category.RESOURCE_TYPE)
                || child.getValueMap().get("retired", Boolean.FALSE)) {
                continue;
            }
            if (hasCategoryChildren(child)) {
                collectLeaves(child, results);
            } else {
                results.add(toJson(child));
            }
        }
    }

    private boolean hasCategoryChildren(final Resource resource)
    {
        for (final Resource child : resource.getChildren()) {
            if (child.isResourceType(Category.RESOURCE_TYPE)) {
                return true;
            }
        }
        return false;
    }

    private JsonObjectBuilder toJson(final Resource category)
    {
        final ValueMap properties = category.getValueMap();
        final JsonObjectBuilder result = Json.createObjectBuilder()
            .add("name", category.getName())
            .add("label", properties.get("label", category.getName()))
            .add("path", category.getPath());
        final String description = properties.get("description", String.class);
        if (description != null) {
            result.add("description", description);
        }
        final String id = properties.get("jcr:uuid", String.class);
        if (id != null) {
            result.add("id", id);
        }
        return result;
    }
}
