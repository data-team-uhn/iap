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

import javax.script.Bindings;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.scripting.sightly.pojo.Use;
import org.jetbrains.annotations.NotNull;

/**
 * Sets the status code of the response from an HTL script. HTL calls only methods that take no arguments, so each
 * status has a method of its own:
 *
 * {@snippet lang=html :
 * <sly data-sly-use.statusCode="io.uhndata.iap.scripting.StatusCodeSetter">${statusCode.created}</sly>
 * }
 *
 * @version $Id$
 * @since 0.1.0
 */
public class StatusCodeSetter implements Use
{
    private SlingJakartaHttpServletResponse response;

    @Override
    public void init(@NotNull final Bindings bindings)
    {
        this.response = (SlingJakartaHttpServletResponse) bindings.get("jakartaResponse");
    }

    /** Sets the status code to {@code 200 OK}. */
    public void ok()
    {
        this.response.setStatus(HttpServletResponse.SC_OK);
    }

    /** Sets the status code to {@code 201 Created}. */
    public void created()
    {
        this.response.setStatus(HttpServletResponse.SC_CREATED);
    }

    /** Sets the status code to {@code 202 Accepted}. */
    public void accepted()
    {
        this.response.setStatus(HttpServletResponse.SC_ACCEPTED);
    }

    /** Sets the status code to {@code 204 No Content}. */
    public void noContent()
    {
        this.response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    /** Sets the status code to {@code 400 Bad Request}. */
    public void badRequest()
    {
        this.response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    /** Sets the status code to {@code 401 Unauthorized}. */
    public void unauthorized()
    {
        this.response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    /** Sets the status code to {@code 403 Forbidden}. */
    public void forbidden()
    {
        this.response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    /** Sets the status code to {@code 404 Not Found}. */
    public void notFound()
    {
        this.response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    /** Sets the status code to {@code 405 Method Not Allowed}. */
    public void methodNotAllowed()
    {
        this.response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    /** Sets the status code to {@code 406 Not Acceptable}. */
    public void notAcceptable()
    {
        this.response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
    }

    /** Sets the status code to {@code 409 Conflict}. */
    public void conflict()
    {
        this.response.setStatus(HttpServletResponse.SC_CONFLICT);
    }

    /** Sets the status code to {@code 423 Locked}. */
    public void locked()
    {
        this.response.setStatus(423);
    }

    /** Sets the status code to {@code 500 Internal Server Error}. */
    public void internalServerError()
    {
        this.response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    /** Sets the status code to {@code 501 Not Implemented}. */
    public void notImplemented()
    {
        this.response.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
    }
}
