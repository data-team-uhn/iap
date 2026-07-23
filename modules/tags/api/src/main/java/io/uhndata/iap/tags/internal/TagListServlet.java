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

import java.io.IOException;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.models.TagDefinition;
import io.uhndata.iap.tags.models.TagsHomepage;

/**
 * Lists the defined tags, as a JSON array of tag definitions wrapped in a result object. Served at
 * {@code /Tags.search.json}, with optional filtering query parameters:
 * <ul>
 * <li>{@code category=<name>}: only tags listing this category (ignoring case)</li>
 * <li>{@code query=<text>}: only tags containing this text (ignoring case) in their name, label, or description</li>
 * <li>{@code target=<path>}: only tags that may be placed on the resource at this path</li>
 * </ul>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { TagsHomepage.RESOURCE_TYPE }, methods = { "GET" },
    selectors = { "search" }, extensions = { "json" })
public class TagListServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 5794086624403109455L;

    @Reference
    private transient TagManager tagManager;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        List<TagDefinition> definitions = this.tagManager.findDefinitions(request.getResourceResolver(),
            request.getParameter("category"), request.getParameter("query"));
        final String target = request.getParameter("target");
        if (target != null && !target.isBlank()) {
            final Resource targetResource = request.getResourceResolver().getResource(target);
            if (targetResource == null) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().write(Json.createObjectBuilder()
                    .add("status", "error")
                    .add("error", "Inaccessible target resource: " + target)
                    .build().toString());
                return;
            }
            definitions = definitions.stream().filter(definition -> definition.appliesTo(targetResource)).toList();
        }

        final JsonArrayBuilder tags = Json.createArrayBuilder();
        definitions.forEach(definition -> tags.add(definition.toDocumentationJson()));
        response.getWriter().write(Json.createObjectBuilder()
            .add("tags", tags)
            .add("total", definitions.size())
            .build().toString());
    }
}
