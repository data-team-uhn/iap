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

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.statistics.api.MetricCalculator;
import io.uhndata.iap.statistics.api.MetricValue;

/**
 * Serves what the metrics currently say, as {@code /Statistics.json}.
 *
 * <p>
 * The definitions under {@code /Statistics} are readable by anyone who can reach the dashboard; the numbers
 * are not, and this is where that is decided. A metric reserved for administrators is left out of the
 * answer entirely rather than returned empty, so that a client cannot tell a restricted metric from one
 * that does not exist — even the sample size of a metric somebody may not see is information about it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    resourceTypes = { "stat/StatisticsHomepage" },
    methods = { "GET" },
    extensions = { "json" })
public class StatisticsEndpoint extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsEndpoint.class);

    @Reference
    private transient MetricCalculator calculator;

    @Override
    public void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        final JsonArrayBuilder metrics = Json.createArrayBuilder();
        this.calculator.computeAll(mayCurate(request)).stream()
            .map(MetricValue::toJson)
            .forEach(metrics::add);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().print(Json.createObjectBuilder().add("metrics", metrics).build().toString());
    }

    /**
     * Whether this reader may see the metrics reserved for administrators.
     *
     * <p>
     * The question asked is whether they may <em>write</em> the definitions. That is deliberately not a
     * second list of who counts as an administrator: the repository already answers it, and a permission
     * and a list can drift apart where a permission and itself cannot.
     * </p>
     *
     * @param request the request being answered
     * @return {@code true} if they may
     */
    private static boolean mayCurate(final SlingJakartaHttpServletRequest request)
    {
        final Session session = request.getResourceResolver().adaptTo(Session.class);
        if (session == null) {
            return false;
        }
        try {
            return session.hasPermission(request.getResource().getPath(), Session.ACTION_SET_PROPERTY);
        } catch (final RepositoryException e) {
            // Answering "no" leaves a curator seeing fewer metrics than they should, which is the harmless
            // way for this to be wrong
            LOGGER.warn("Could not tell whether {} may curate the metrics: {}", session.getUserID(),
                e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(StatisticsEndpoint.class, "mayCurate"));
            return false;
        }
    }
}
