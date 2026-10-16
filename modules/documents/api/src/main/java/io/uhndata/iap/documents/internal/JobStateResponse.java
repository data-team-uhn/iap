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

import jakarta.json.JsonObject;

import org.apache.sling.api.SlingJakartaHttpServletResponse;

/**
 * Writes the state of a parse job, which {@link ParseServlet} and {@link ParseCallbackServlet} both answer with. A
 * request they refuse is answered through {@link io.uhndata.iap.utils.JsonResponses} instead.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class JobStateResponse
{
    private JobStateResponse()
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
    // A job's state is content: its own error, if it failed, is the job's, not this request's
    @SuppressWarnings("checkstyle:directResponseWriter")
    static void write(final SlingJakartaHttpServletResponse response, final int status, final JsonObject body)
        throws IOException
    {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().print(body.toString());
    }
}
