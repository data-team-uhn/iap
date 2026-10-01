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
package io.uhndata.iap.documents.internal;

import java.io.IOException;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.SlingJakartaHttpServletResponse;

/**
 * Writes the JSON bodies that {@link ParseServlet} and {@link ParseCallbackServlet} answer with. Both endpoints
 * are consumed by the same clients, so the content type, the encoding and the shape of an error body are settled
 * here rather than in each servlet.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class JsonResponse
{
    private JsonResponse()
    {
        // Utility
    }

    /**
     * Send a JSON body with the given status.
     *
     * @param response the response to write to
     * @param status the HTTP status to send
     * @param body the body to send
     * @throws IOException if the response cannot be written
     */
    static void write(final SlingJakartaHttpServletResponse response, final int status, final JsonObject body)
        throws IOException
    {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().print(body.toString());
    }

    /**
     * Send an error as a JSON body carrying a single {@link ParseJob#PN_ERROR} key, so a client has one shape to
     * read whichever endpoint refused it.
     *
     * @param response the response to write to
     * @param status the HTTP status to send
     * @param message what went wrong
     * @throws IOException if the response cannot be written
     */
    static void error(final SlingJakartaHttpServletResponse response, final int status, final String message)
        throws IOException
    {
        write(response, status, Json.createObjectBuilder().add(ParseJob.PN_ERROR, message).build());
    }
}
