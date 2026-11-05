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
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.utils.JsonResponses;

/**
 * Answers {@code adminSummary.json} for a tool's dashboard widget. The format is described in
 * {@code docs/administration.md}, under "Summary figures".
 *
 * <p>
 * A subclass computes the figures in {@link #summarize}, and registers itself on its own resource type with the
 * {@value #SUMMARY_SELECTOR} selector. This class writes the answer. If the summary cannot be read, it answers
 * {@code 500} with a {@link JsonResponses#writeError} body.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class AdminSummaryServlet extends SlingJakartaSafeMethodsServlet
{
    /** The selector every summary answers on. */
    public static final String SUMMARY_SELECTOR = "adminSummary";

    private static final long serialVersionUID = 1L;

    private static final String UNREADABLE_MESSAGE = "The summary cannot be read";

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminSummaryServlet.class);

    @Override
    // The summary is content, figures rather than a command's answer
    @SuppressWarnings("checkstyle:directResponseWriter")
    public final void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        final JsonObjectBuilder body;
        try {
            body = this.summarize(request);
        } catch (final SummaryUnavailableException e) {
            fail(request, response, e.getMessage(), e.getCause());
            return;
        } catch (final RuntimeException e) {
            // An unexpected failure gets the same answer, with a generic reason
            fail(request, response, UNREADABLE_MESSAGE, e);
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body.build().toString());
    }

    /**
     * Logs a failure and answers with it.
     *
     * @param request the request that failed
     * @param response the HTTP response to write the error into
     * @param message what could not be read, as the client is told
     * @param cause the underlying failure, or {@code null} if there is none
     * @throws IOException if the response's body cannot be written
     */
    private static void fail(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response, final String message, final Throwable cause)
        throws IOException
    {
        LOGGER.warn("{}: {}", request.getResource().getPath(), message, cause);
        JsonResponses.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message);
    }

    /**
     * The figures of the summary.
     *
     * @param request the request, whose resource is the one to summarize
     * @return the figures in display order, each under a name unique within the summary, such as {@code total}
     * @throws SummaryUnavailableException if the summary cannot be read
     */
    @NotNull
    protected abstract JsonObjectBuilder summarize(@NotNull SlingJakartaHttpServletRequest request)
        throws SummaryUnavailableException;

    /**
     * An exact count.
     *
     * @param label what the figure is a count of, as the widget shows it, such as {@code Recorded in total}
     * @param value how many were counted
     * @return the figure
     */
    @NotNull
    protected static JsonObjectBuilder count(@NotNull final String label, final long value)
    {
        return count(label, value, false);
    }

    /**
     * A count that may have stopped at a bound.
     *
     * @param label what the figure is a count of, as the widget shows it
     * @param value how many were counted
     * @param approximate whether counting stopped early, which makes {@code value} a lower bound
     * @return the figure
     */
    @NotNull
    protected static JsonObjectBuilder count(@NotNull final String label, final long value, final boolean approximate)
    {
        final JsonObjectBuilder figure = Json.createObjectBuilder().add("label", label).add("value", value);
        if (approximate) {
            figure.add("approximate", true);
        }
        return figure;
    }

    /**
     * A count that could not be taken. The figure keeps its place in the summary, with a {@code null} value.
     *
     * @param label what the figure is a count of, as the widget shows it
     * @return the figure
     */
    @NotNull
    protected static JsonObjectBuilder unknown(@NotNull final String label)
    {
        return Json.createObjectBuilder().add("label", label).addNull("value");
    }

    /**
     * A state that is on or off.
     *
     * @param label what the state is, as the widget shows it
     * @param value whether it is on
     * @return the figure
     */
    @NotNull
    protected static JsonObjectBuilder state(@NotNull final String label, final boolean value)
    {
        return Json.createObjectBuilder().add("label", label).add("value", value);
    }
}
