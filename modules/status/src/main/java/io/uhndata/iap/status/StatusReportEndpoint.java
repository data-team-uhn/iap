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
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

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
import io.uhndata.iap.utils.JsonResponses;
import io.uhndata.iap.utils.UserIds;

/**
 * Serves the status reports. {@code /system/status} answers with a JSON array of the reports, and
 * {@code /system/status.txt} with their texts alone, separated by blank lines.
 *
 * <p>
 * Anybody may read it without logging in, so a monitoring tool can poll it. Only the {@code admin} user gets the
 * privileged reports. Everyone else, logged in or not, gets the unprivileged ones.
 * </p>
 *
 * <p>
 * Two optional query parameters select the reports. {@code targetStatus} is the lowest level to include, in capitals,
 * and {@code INFO} by default. A value that names no level gets a 400. {@code tags} may be repeated, and selects the
 * reporters with one of those tags.
 * </p>
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
    // The reports are content, not a command's answer
    @SuppressWarnings("checkstyle:directResponseWriter")
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        final boolean unprivileged = !"admin".equals(UserIds.canonical(request.getResourceResolver()));
        final String requestedStatus = request.getParameter("targetStatus");
        final StatusReport.Status targetStatus;
        try {
            targetStatus = requestedStatus == null || requestedStatus.isBlank()
                ? StatusReport.Status.INFO : StatusReport.Status.valueOf(requestedStatus);
        } catch (final IllegalArgumentException e) {
            JsonResponses.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "targetStatus must be one of "
                + Arrays.stream(StatusReport.Status.values()).map(Enum::name).collect(Collectors.joining(", ")));
            return;
        }
        response.setCharacterEncoding("UTF-8");
        final Set<String> tags = requestedTags(request);
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

    /**
     * The tags a request selects reporters by. A tag repeated in the query, as in {@code ?tags=a&tags=a}, counts once.
     *
     * @param request the request to read the {@code tags} parameter of
     * @return the distinct tags, empty if the request names none
     */
    private static Set<String> requestedTags(final SlingJakartaHttpServletRequest request)
    {
        final String[] tags = request.getParameterValues("tags");
        return tags == null ? Collections.emptySet() : Set.copyOf(Arrays.asList(tags));
    }
}
