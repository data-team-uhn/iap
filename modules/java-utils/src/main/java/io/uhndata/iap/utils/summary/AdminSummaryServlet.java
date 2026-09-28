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
package io.uhndata.iap.utils.summary;

import java.io.IOException;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers {@code adminSummary.json} for a tool's dashboard widget. The format is described in
 * {@code docs/administration.md}, under "Summary figures".
 *
 * <p>
 * A subclass computes the figures in {@link #summarize}, and registers itself on its own resource type with the
 * {@link #SELECTOR} selector. This class writes the answer, or an error status when the summary cannot be read.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class AdminSummaryServlet extends SlingJakartaSafeMethodsServlet
{
    /** The selector every summary answers on. */
    public static final String SELECTOR = "adminSummary";

    private static final long serialVersionUID = 1L;

    private static final String UNREADABLE = "The summary cannot be read";

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminSummaryServlet.class);

    @Override
    public final void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        JsonObjectBuilder body;
        try {
            body = this.summarize(request);
            response.setStatus(HttpServletResponse.SC_OK);
        } catch (final SummaryUnavailableException e) {
            body = failed(request, e.getMessage(), e.getCause());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } catch (final RuntimeException e) {
            // A failure the subclass did not foresee still answers in the format the widget reads
            body = failed(request, UNREADABLE, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body.build().toString());
    }

    /**
     * Logs a failure and builds the answer reporting it.
     *
     * @param request the request that failed
     * @param message what could not be read, as the client is told
     * @param cause the underlying failure, or {@code null} if there is none
     * @return a JSON object builder holding the error
     */
    private static JsonObjectBuilder failed(final SlingJakartaHttpServletRequest request, final String message,
        final Throwable cause)
    {
        LOGGER.warn("{}: {}", request.getResource().getPath(), message, cause);
        return Json.createObjectBuilder().add("error", message);
    }

    /**
     * The figures of the summary, in the order they are displayed, keyed by a name unique within it.
     *
     * @param request the request, whose resource is the one to summarize
     * @return a JSON object builder holding the figures
     * @throws SummaryUnavailableException if the summary cannot be read
     */
    protected abstract JsonObjectBuilder summarize(SlingJakartaHttpServletRequest request)
        throws SummaryUnavailableException;

    /**
     * An exact count.
     *
     * @param label what the figure is a count of
     * @param value how many were counted
     * @return a JSON object builder holding the figure
     */
    protected static JsonObjectBuilder count(final String label, final long value)
    {
        return count(label, value, false);
    }

    /**
     * A count that may have stopped at a bound.
     *
     * @param label what the figure is a count of
     * @param value how many were counted
     * @param approximate whether counting stopped early, making {@code value} a lower bound
     * @return a JSON object builder holding the figure
     */
    protected static JsonObjectBuilder count(final String label, final long value, final boolean approximate)
    {
        final JsonObjectBuilder figure = Json.createObjectBuilder().add("label", label).add("value", value);
        if (approximate) {
            figure.add("approximate", true);
        }
        return figure;
    }

    /**
     * A count that could not be taken. The figure keeps its place in the summary, and the widget shows its value
     * as unknown.
     *
     * @param label what the figure is a count of
     * @return a JSON object builder holding the figure
     */
    protected static JsonObjectBuilder unknown(final String label)
    {
        return Json.createObjectBuilder().add("label", label).addNull("value");
    }

    /**
     * A state that is on or off.
     *
     * @param label what the state is
     * @param value whether it is on
     * @return a JSON object builder holding the figure
     */
    protected static JsonObjectBuilder state(final String label, final boolean value)
    {
        return Json.createObjectBuilder().add("label", label).add("value", value);
    }
}
