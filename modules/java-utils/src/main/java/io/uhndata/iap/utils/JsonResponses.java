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
package io.uhndata.iap.utils;

import java.io.IOException;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.jetbrains.annotations.NotNull;

/**
 * Writes what an endpoint answers a command with, in one shape: {@code {"status": outcome, ...details}}. The outcome
 * is a short word a client can switch on, such as {@code ok} or {@code archived}; an error is the outcome
 * {@code error}, with the reason under {@code error}. Content an endpoint serves, such as a resource or a listing, is
 * not a command's answer and keeps its own shape.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class JsonResponses
{
    /** The key holding the outcome. */
    public static final String STATUS_KEY = "status";

    /** The key holding an error's reason. */
    public static final String ERROR_KEY = "error";

    /** The outcome of a request that failed. */
    public static final String ERROR_OUTCOME = "error";

    private JsonResponses()
    {
        // Utility class
    }

    /**
     * Answers with an outcome and nothing more, such as {@code {"status": "ok"}}.
     *
     * @param response the HTTP response to write into
     * @param httpStatus the HTTP status code to answer with, such as {@code 200} or {@code 201}
     * @param outcome a short word a client can switch on, saying how the request went, such as {@code ok},
     *            {@code created} or {@code archived}; a failure is answered with {@link #writeError} instead
     * @throws IOException if the response's body cannot be written
     */
    public static void write(@NotNull final SlingJakartaHttpServletResponse response, final int httpStatus,
        @NotNull final String outcome) throws IOException
    {
        write(response, httpStatus, outcome, Json.createObjectBuilder());
    }

    /**
     * Answers with an outcome and the details that go with it, such as
     * {@code {"status": "ok", "acknowledgement": "/LoggedErrors/..."}}.
     *
     * @param response the HTTP response to write into
     * @param httpStatus the HTTP status code to answer with, such as {@code 200} or {@code 201}
     * @param outcome a short word a client can switch on, saying how the request went, such as {@code ok},
     *            {@code created} or {@code archived}; a failure is answered with {@link #writeError} instead
     * @param details the keys written beside {@code status}, which must not include {@code status} or {@code error}
     * @throws IOException if the response's body cannot be written
     * @throws IllegalArgumentException if the details set {@code status} or {@code error}
     */
    public static void write(@NotNull final SlingJakartaHttpServletResponse response, final int httpStatus,
        @NotNull final String outcome, @NotNull final JsonObjectBuilder details) throws IOException
    {
        final JsonObject extra = details.build();
        if (extra.containsKey(STATUS_KEY) || extra.containsKey(ERROR_KEY)) {
            throw new IllegalArgumentException("A response's details cannot set " + STATUS_KEY + " or " + ERROR_KEY);
        }
        send(response, httpStatus, body(outcome, extra));
    }

    /**
     * Answers with an error: {@code {"status": "error", "error": reason}}. The reason is read by people, so it says
     * what went wrong in their terms, and never carries an exception's message, which can name the instance's
     * infrastructure. A response already committed is left as it is: its status is on the wire, and a second body
     * after the first is not something a client can parse.
     *
     * @param response the HTTP response to write into; left as it is if already committed
     * @param httpStatus the HTTP status code to answer with, such as {@code 400} or {@code 500}
     * @param reason what went wrong, in words for the person who will read it, such as {@code "The target is not
     *            readable"}; never an exception's message
     * @throws IOException if the response's body cannot be written
     */
    public static void writeError(@NotNull final SlingJakartaHttpServletResponse response, final int httpStatus,
        @NotNull final String reason) throws IOException
    {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        send(response, httpStatus, body(ERROR_OUTCOME, Json.createObjectBuilder().add(ERROR_KEY, reason).build()));
    }

    private static JsonObject body(final String outcome, final JsonObject details)
    {
        final JsonObjectBuilder body = Json.createObjectBuilder().add(STATUS_KEY, outcome);
        details.forEach(body::add);
        return body.build();
    }

    // The one place a JSON answer reaches the response
    @SuppressWarnings("checkstyle:directResponseWriter")
    private static void send(final SlingJakartaHttpServletResponse response, final int httpStatus,
        final JsonObject body) throws IOException
    {
        response.setStatus(httpStatus);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body.toString());
    }
}
