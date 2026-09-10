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
import java.io.Writer;
import java.math.BigDecimal;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import jakarta.servlet.Servlet;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.utils.PaginatedJsonResponse;

/**
 * Servlet that exposes the LLM configuration catalog (providers and their models, with all parameters)
 * and the active selection, and lets administrators change which provider and model are active.
 *
 * <p>Endpoint: bound to the {@link LLMConfigurationServiceImpl#SELECTION_PATH} node, selector {@code llm},
 * extension {@code json}, i.e. {@code /apps/iap/config/LLM.llm.json}. The catalog itself is read separately,
 * from {@link LLMConfigurationServiceImpl#CATALOG_PATH}; see there for why the two are apart.
 *
 * <p>{@code GET .../LLM.llm.json} returns:
 * {@snippet lang=json :
 * {"activeProvider": "...", "activeModel": "...", "providers": [{"name": "...", ..., "models": [{...}]}]}
 * }
 *
 * <p>{@code POST .../LLM.llm.json} with parameters {@code activeProvider} and {@code activeModel} updates the
 * active selection and returns the refreshed catalog.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    resourceTypes = { "llm/Configuration" },
    selectors = { "llm" },
    extensions = { "json" },
    methods = { "GET", "POST" })
public class LLMConfigServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = -7913246809238126820L;

    private static final String ACTIVE_PROVIDER = "activeProvider";

    private static final String ACTIVE_MODEL = "activeModel";

    private static final String PROVIDER_RESOURCE_TYPE = "llm/Provider";

    private static final String MODEL_RESOURCE_TYPE = "llm/Model";

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        response.setContentType("application/json;charset=UTF-8");
        final Resource selection = request.getResource();
        try (Writer out = response.getWriter()) {
            out.write(buildCatalog(selection, catalog(selection)).toString());
        }
    }

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        response.setContentType("application/json;charset=UTF-8");

        final Resource selection = request.getResource();
        final Resource catalog = catalog(selection);
        final String provider = request.getParameter(ACTIVE_PROVIDER);
        final String model = request.getParameter(ACTIVE_MODEL);

        if (StringUtils.isBlank(provider) || StringUtils.isBlank(model)) {
            PaginatedJsonResponse.writeError(response, 400, "Both 'activeProvider' and 'activeModel' are required");
            return;
        }

        final Resource providerResource = validChild(catalog, provider, PROVIDER_RESOURCE_TYPE);
        if (providerResource == null) {
            PaginatedJsonResponse.writeError(response, 400, "The requested provider is not in the catalog");
            return;
        }
        if (validChild(providerResource, model, MODEL_RESOURCE_TYPE) == null) {
            PaginatedJsonResponse.writeError(response, 400, "The requested model is not offered by that provider");
            return;
        }

        final ModifiableValueMap properties = selection.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            PaginatedJsonResponse.writeError(response, 403, "Not allowed to modify the LLM configuration");
            return;
        }
        properties.put(ACTIVE_PROVIDER, provider);
        properties.put(ACTIVE_MODEL, model);
        selection.getResourceResolver().commit();

        try (Writer out = response.getWriter()) {
            out.write(buildCatalog(selection, catalog).toString());
        }
    }

    /**
     * Resolve the catalog resource the selection resource points into.
     *
     * @param selection the bound selection resource
     * @return the catalog resource, or {@code null} if it does not exist
     */
    private static Resource catalog(final Resource selection)
    {
        return selection.getResourceResolver().getResource(LLMConfigurationServiceImpl.CATALOG_PATH);
    }

    /**
     * Resolve and validate a catalog child by name: it must be a direct child of {@code parent} (a name
     * containing {@code /} is refused before ever being resolved, since {@link Resource#getChild} treats it as
     * a path rather than a single node name) and must carry the expected resource type, so a request cannot
     * point the active selection at an unrelated node.
     *
     * @param parent the catalog or provider resource to look under, may be {@code null} when the catalog itself
     *            is missing
     * @param name the requested child name, from the request
     * @param expectedResourceType the resource type a genuine catalog entry carries
     * @return the validated child, or {@code null} when it does not exist, is reached through a path, or is
     *         not of the expected type
     */
    private static Resource validChild(final Resource parent, final String name, final String expectedResourceType)
    {
        if (parent == null || name.indexOf('/') >= 0) {
            return null;
        }
        final Resource child = parent.getChild(name);
        return child != null && child.isResourceType(expectedResourceType) ? child : null;
    }

    private JsonObject buildCatalog(final Resource selection, final Resource catalog)
    {
        final JsonObjectBuilder root = Json.createObjectBuilder();
        final ValueMap selectionProperties = selection.getValueMap();
        addString(root, ACTIVE_PROVIDER, selectionProperties.get(ACTIVE_PROVIDER, String.class));
        addString(root, ACTIVE_MODEL, selectionProperties.get(ACTIVE_MODEL, String.class));

        final JsonArrayBuilder providers = Json.createArrayBuilder();
        if (catalog != null) {
            for (final Resource provider : catalog.getChildren()) {
                final JsonObjectBuilder providerJson = propertiesToJson(provider);
                final JsonArrayBuilder models = Json.createArrayBuilder();
                for (final Resource model : provider.getChildren()) {
                    models.add(propertiesToJson(model));
                }
                providerJson.add("models", models);
                providers.add(providerJson);
            }
        }
        root.add("providers", providers);
        return root.build();
    }

    private JsonObjectBuilder propertiesToJson(final Resource resource)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder();
        json.add("name", resource.getName());
        for (final Map.Entry<String, Object> entry : resource.getValueMap().entrySet()) {
            final String key = entry.getKey();
            if (!key.startsWith("jcr:") && !key.startsWith("sling:")) {
                addValue(json, key, entry.getValue());
            }
        }
        return json;
    }

    private void addValue(final JsonObjectBuilder json, final String key, final Object value)
    {
        if (value instanceof Object[]) {
            final JsonArrayBuilder array = Json.createArrayBuilder();
            for (final Object item : (Object[]) value) {
                array.add(toJsonValue(item));
            }
            json.add(key, array);
        } else if (value != null) {
            json.add(key, toJsonValue(value));
        }
    }

    /**
     * Convert a JCR property value to the JSON value it should be rendered as. {@link BigDecimal} is handled
     * before the general {@link Number} case, which narrows to {@code long} and would otherwise truncate a
     * decimal value.
     *
     * @param value a single JCR property value, never {@code null} or an array
     * @return the equivalent JSON value
     */
    private static JsonValue toJsonValue(final Object value)
    {
        if (value instanceof BigDecimal) {
            return Json.createValue((BigDecimal) value);
        }
        if (value instanceof Double || value instanceof Float) {
            return Json.createValue(((Number) value).doubleValue());
        }
        if (value instanceof Number) {
            return Json.createValue(((Number) value).longValue());
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? JsonValue.TRUE : JsonValue.FALSE;
        }
        return Json.createValue(value.toString());
    }

    private void addString(final JsonObjectBuilder json, final String key, final String value)
    {
        if (value != null) {
            json.add(key, value);
        }
    }
}
