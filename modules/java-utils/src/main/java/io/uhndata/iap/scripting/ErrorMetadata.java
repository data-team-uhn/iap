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
package io.uhndata.iap.scripting;

import java.util.Map;

import javax.script.Bindings;

import jakarta.json.Json;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.scripting.sightly.pojo.Use;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The error an error handler script is answering. It reads the status code and the message from the
 * {@code jakarta.servlet.error.*} request attributes, and sets that status code on the response. To use it in an
 * error handler's HTL:
 *
 * {@snippet lang=html :
 * <sly data-sly-use.error="io.uhndata.iap.scripting.ErrorMetadata"></sly>
 * }
 *
 * @version $Id$
 * @since 0.1.0
 */
public class ErrorMetadata implements Use
{
    /** The reason phrase for each status code it names, used when the error has no message. */
    private static final Map<Integer, String> REASON_PHRASES = Map.ofEntries(
        Map.entry(HttpServletResponse.SC_BAD_REQUEST, "Bad Request"),
        Map.entry(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"),
        Map.entry(HttpServletResponse.SC_FORBIDDEN, "Forbidden"),
        Map.entry(HttpServletResponse.SC_NOT_FOUND, "Not Found"),
        Map.entry(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method Not Allowed"),
        Map.entry(HttpServletResponse.SC_REQUEST_TIMEOUT, "Request Timeout"),
        Map.entry(HttpServletResponse.SC_CONFLICT, "Conflict"),
        Map.entry(HttpServletResponse.SC_PRECONDITION_FAILED, "Precondition Failed"),
        Map.entry(HttpServletResponse.SC_EXPECTATION_FAILED, "Expectation Failed"),
        Map.entry(422, "Unprocessable Entity"),
        Map.entry(423, "Locked"),
        Map.entry(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error"),
        Map.entry(HttpServletResponse.SC_NOT_IMPLEMENTED, "Not Implemented"),
        Map.entry(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service Unavailable"));

    private int statusCode;

    private String statusMessage;

    private boolean jsonRequested;

    @Override
    public void init(@NotNull final Bindings bindings)
    {
        final SlingJakartaHttpServletRequest request = (SlingJakartaHttpServletRequest) bindings.get("jakartaRequest");
        final SlingJakartaHttpServletResponse response =
            (SlingJakartaHttpServletResponse) bindings.get("jakartaResponse");
        final Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        this.statusCode = code instanceof Number ? ((Number) code).intValue()
            : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        final Object message = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        this.statusMessage = message instanceof String && StringUtils.isNotBlank((String) message) ? (String) message
            : REASON_PHRASES.getOrDefault(this.statusCode, "Error");
        this.jsonRequested = "application/json".equals(request.getHeader("Accept"));
        response.setStatus(this.statusCode);
    }

    /**
     * The status code of the error being handled.
     *
     * @return the HTTP status code, such as {@code 404}; {@code 500} if the request does not name one
     */
    public int getStatusCode()
    {
        return this.statusCode;
    }

    /**
     * The message of the error being handled.
     *
     * @return the error's message; if it has none, the reason phrase for the status code, such as
     *         {@code Not Found}, or {@code Error} for a code without one
     */
    @NotNull
    public String getStatusMessage()
    {
        return this.statusMessage;
    }

    /**
     * The error as JSON, for a client that asked for JSON rather than an HTML page.
     *
     * @return an object with {@code status} set to {@code error}, {@code status.message} and {@code status.code};
     *         {@code null} unless the request's {@code Accept} header is exactly {@code application/json}
     */
    @Nullable
    public String getJson()
    {
        if (!this.jsonRequested) {
            return null;
        }
        return Json.createObjectBuilder()
            .add("status", "error")
            .add("status.message", this.statusMessage)
            .add("status.code", this.statusCode)
            .build()
            .toString();
    }
}
