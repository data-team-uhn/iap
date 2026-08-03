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
package io.uhndata.iap.startup.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

/**
 * The default servlet of the {@link StartupPlaceholderContext}, serving the static "starting up" page. The whiteboard
 * dispatcher only routes requests into a context that has a matching servlet, so without this catch-all the
 * placeholder context — and with it the {@link StartupGateFilter} — would simply be skipped, and early visitors would
 * get the raw container 404. Since the placeholder context has the lowest possible ranking, this servlet only ever
 * sees requests that no real context could handle, which is exactly the early startup phase.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Servlet.class, property = {
    // "/" is the whiteboard notation for a context's default servlet, catching everything unmatched
    "osgi.http.whiteboard.servlet.pattern=/",
    "osgi.http.whiteboard.context.select=(osgi.http.whiteboard.context.name=io.uhndata.iap.startup)"
})
public final class StartupPlaceholderServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;

    /** The static "starting up" page, also served by the launcher itself before the framework is up. */
    private final String startupPage;

    /**
     * Loads the startup page from the bundle.
     *
     * @throws IOException if the startup page cannot be read from the classpath, which means a broken build
     */
    @Activate
    public StartupPlaceholderServlet() throws IOException
    {
        try (InputStream stub = Objects.requireNonNull(StartupPlaceholderServlet.class.getResourceAsStream(
            "/custom_index.html"))) {
            this.startupPage = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(stub.readAllBytes())).toString();
        }
    }

    @Override
    protected void service(final HttpServletRequest request, final HttpServletResponse response) throws IOException
    {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("text/html;charset=UTF-8");
        // The page changes from one moment to the next, and the stub itself must never be cached
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(this.startupPage);
    }
}
