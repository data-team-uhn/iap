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
package io.uhndata.iap.conditions.internal;

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
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionTypes;
import io.uhndata.iap.conditions.spi.CandidateProvider;

/**
 * Lists what an operand of one source can name, for a condition on the requested content:
 * {@code <path>.conditionCandidates.json?source=answer}. The answer is a JSON list of candidates, empty when the
 * source lists none here.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Servlet.class)
@SlingServletResourceTypes(
    resourceTypes = "sling/servlet/default",
    selectors = ConditionCandidatesServlet.SELECTOR,
    extensions = "json",
    methods = HttpConstants.METHOD_GET)
public class ConditionCandidatesServlet extends SlingJakartaSafeMethodsServlet
{
    /** The selector the candidates are asked for with. */
    public static final String SELECTOR = "conditionCandidates";

    private static final long serialVersionUID = 1L;

    @Reference
    private transient ConditionTypes types;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        final String source = request.getParameter("source");
        if (source == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Ask for the candidates of a source");
            return;
        }
        final CandidateProvider provider = this.types.getCandidateProvider(source);
        final JsonArrayBuilder candidates = Json.createArrayBuilder();
        if (provider != null) {
            provider.getCandidates(request.getResource()).forEach(candidate -> candidates.add(candidate.toJson()));
        }
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(candidates.build().toString());
    }
}
