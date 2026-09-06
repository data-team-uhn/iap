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
package io.uhndata.iap.statistics.internal;

import java.io.IOException;
import java.util.Calendar;

import jakarta.json.Json;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Works the metrics out again on request, at {@code POST /Statistics.refresh.json}.
 *
 * <p>
 * The figures are refreshed on a schedule, which is what makes reading them free; this is for the cases
 * where waiting for the next run is not reasonable — a demonstration, a freshly loaded set of test data,
 * or a definition somebody has just edited and wants to see the effect of.
 * </p>
 *
 * <p>
 * It answers when the work is done rather than when it has been started, because the one thing a caller
 * asking for this wants to know is whether the numbers they are about to read are the new ones.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { "stat/StatisticsHomepage" }, methods = { "POST" },
    selectors = { "refresh" }, extensions = { "json" })
public class StatisticsRefreshServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = 1L;

    private static final String STATUS = "status";

    @Reference
    private transient MetricStore store;

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!Curators.includes(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write(Json.createObjectBuilder()
                .add(STATUS, "error")
                .add("error", "Refreshing the metrics requires write access to " + Definitions.ROOT)
                .build().toString());
            return;
        }

        final Calendar computedAt = this.store.refresh();
        if (computedAt == null) {
            // Reported rather than left to look like success: the previous figures are still there, and a
            // caller who asked for new ones would otherwise read the old ones as new
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(Json.createObjectBuilder()
                .add(STATUS, "error")
                .add("error", "The metrics could not be worked out")
                .build().toString());
            return;
        }
        response.getWriter().write(Json.createObjectBuilder()
            .add(STATUS, "ok")
            .add("computedAt", computedAt.toInstant().toString())
            .build().toString());
    }
}
