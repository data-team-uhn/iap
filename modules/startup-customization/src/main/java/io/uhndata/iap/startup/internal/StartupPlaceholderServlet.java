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
 * Serves the "starting up" page for every request in the {@link StartupPlaceholderContext}. The whiteboard sends a
 * request only into a context that has a servlet for it. Without this one, it would skip the placeholder context, and
 * the {@link StartupGateFilter} in it. The context has the lowest ranking, so this servlet only gets requests that no
 * other context takes. The gate disables this component when it retires.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Servlet.class, property = {
    // "/" makes this the context's default servlet, which gets every request no other servlet matches
    "osgi.http.whiteboard.servlet.pattern=/",
    "osgi.http.whiteboard.context.select=(osgi.http.whiteboard.context.name=io.uhndata.iap.startup)"
})
public final class StartupPlaceholderServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;

    /** The "starting up" page, as HTML. */
    private final String startupPage;

    /**
     * Loads the startup page from the bundle.
     *
     * @throws IOException if the startup page cannot be read from the bundle, which means a broken build
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
    // The startup page is HTML
    @SuppressWarnings("checkstyle:directResponseWriter")
    protected void service(final HttpServletRequest request, final HttpServletResponse response) throws IOException
    {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("text/html;charset=UTF-8");
        // The real page replaces the stub at this URL once the gate opens, so the browser must not keep the stub
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(this.startupPage);
    }
}
