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
package io.uhndata.iap.status;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.status.api.StatusReportManager;
import io.uhndata.iap.status.spi.StatusReport;

/**
 * Serves the {@link StatusReportManager}'s reports at {@code /system/status} (JSON) and {@code /system/status.txt}
 * (plain text). The endpoint is accessible without authentication so that monitoring tools can poll it, but only an
 * administrator gets reports containing sensitive information. Optional query parameters: {@code targetStatus}
 * (minimum report level to include, {@code INFO} by default) and {@code tags} (repeatable, only include reporters
 * with one of these tags).
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class }, property = { "sling.auth.requirements=-/system/status" })
@SlingServletPaths(value = "/system/status")
public class StatusReportEndpoint extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = -7020359624123539871L;

    @Reference
    private transient StatusReportManager manager;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        response.setCharacterEncoding("UTF-8");
        final boolean unprivileged = !("admin".equals(request.getRemoteUser()));
        final String requestedStatus = request.getParameter("targetStatus");
        final StatusReport.Status targetStatus;
        try {
            targetStatus = requestedStatus == null || requestedStatus.isBlank()
                ? StatusReport.Status.INFO : StatusReport.Status.valueOf(requestedStatus);
        } catch (final IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().print(Json.createObjectBuilder()
                .add("status", "error")
                .add("error", "Invalid targetStatus: " + requestedStatus)
                .build().toString());
            return;
        }
        final Set<String> tags = request.getParameterValues("tags") == null ? Collections.emptySet()
            : Set.of(request.getParameterValues("tags"));
        if ("txt".equals(request.getRequestPathInfo().getExtension())) {
            final String result = String.join("\n\n",
                this.manager.getReports(unprivileged, targetStatus, tags).stream()
                    .map(StatusReport::getText)
                    .map(text -> text == null ? "" : text)
                    .toList());
            response.setContentType("text/plain");
            response.getWriter().print(result);
        } else {
            final JsonArrayBuilder results = Json.createArrayBuilder();
            this.manager.getReports(unprivileged, targetStatus, tags).stream()
                .map(StatusReport::toJson)
                .forEach(results::add);
            response.setContentType("application/json");
            response.getWriter().print(results.build().toString());
        }
    }
}
