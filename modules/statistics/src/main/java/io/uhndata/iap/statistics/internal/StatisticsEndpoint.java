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
import java.io.StringReader;
import java.util.Calendar;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonException;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
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

/**
 * Serves what the metrics last said, as {@code /Statistics.json}.
 *
 * <p>
 * A read and nothing more: the numbers are worked out on a schedule, and this hands over what that last
 * produced. It answers in milliseconds however much history a deployment has accumulated, which is the
 * whole reason the two are separate.
 * </p>
 *
 * <p>
 * <strong>The answer says when it was worked out</strong>, so that a page can tell its reader how old
 * the figures are instead of leaving them to assume they are current.
 * </p>
 *
 * <p>
 * Who may see which number is decided here. A metric reserved for administrators is left out of the
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
    private transient MetricStore store;

    @Override
    public void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        final MetricStore.Stored stored = this.store.read(Curators.includes(request));
        final JsonArrayBuilder metrics = Json.createArrayBuilder();
        stored.values().forEach(value -> parse(value, metrics));
        final JsonObjectBuilder answer = Json.createObjectBuilder();
        final Calendar computedAt = stored.computedAt();
        if (computedAt == null) {
            answer.addNull("computedAt");
        } else {
            answer.add("computedAt", computedAt.toInstant().toString());
        }
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().print(answer.add("metrics", metrics).build().toString());
    }

    /**
     * Adds one stored metric to the answer, dropping it if what was stored is not readable — a metric
     * whose stored form somehow got mangled should cost that metric and not the whole dashboard.
     *
     * @param value the stored JSON
     * @param metrics the answer being built
     */
    private static void parse(final String value, final JsonArrayBuilder metrics)
    {
        try (JsonReader reader = Json.createReader(new StringReader(value))) {
            metrics.add(reader.readObject());
        } catch (final JsonException e) {
            LOGGER.warn("A stored metric could not be read back and was left out: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(StatisticsEndpoint.class, "parse"));
        }
    }
}
