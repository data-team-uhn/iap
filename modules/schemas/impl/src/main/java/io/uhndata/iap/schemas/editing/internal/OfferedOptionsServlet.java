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
package io.uhndata.iap.schemas.editing.internal;

import java.io.IOException;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.utils.JsonResponses;

/**
 * The answers a question offers, as {@code GET <question>.offeredOptions.json}: the same list the submitter's form
 * and a model reading the documents get ({@link Question#getOfferedOptions()}), so the schema editor builds its
 * conditions on the values an answer can really hold.
 *
 * {@snippet lang=json :
 * {"status": "ok", "options": [{"value": "/Categories/Prospective/Chart", "label": "Chart review", "description": ""}]}
 * }
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    resourceTypes = Question.RESOURCE_TYPE,
    selectors = OfferedOptionsServlet.OPTIONS_SELECTOR,
    extensions = "json",
    methods = { HttpConstants.METHOD_GET })
public class OfferedOptionsServlet extends SlingJakartaSafeMethodsServlet
{
    /** The selector this servlet answers. */
    static final String OPTIONS_SELECTOR = "offeredOptions";

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        final Question question = request.getResource().adaptTo(Question.class);
        if (question == null) {
            JsonResponses.writeError(response, HttpServletResponse.SC_NOT_FOUND, "This is not a question");
            return;
        }
        final JsonArrayBuilder options = Json.createArrayBuilder();
        question.getOfferedOptions().forEach(option -> options.add(Json.createObjectBuilder()
            .add("value", option.value())
            .add("label", option.label())
            .add("description", option.description())));
        JsonResponses.write(response, HttpServletResponse.SC_OK, "ok",
            Json.createObjectBuilder().add("options", options));
    }
}
