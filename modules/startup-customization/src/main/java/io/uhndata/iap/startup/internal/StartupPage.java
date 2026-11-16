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
import java.io.Serializable;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The "starting up" page, and the answer that shows it to a visitor while IAP is not ready.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class StartupPage implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** Where the bundle keeps the page, as a classpath resource path. */
    private static final String BUNDLED_PAGE = "/custom_index.html";

    /** The page, as HTML. */
    private final String html;

    /**
     * Creates a startup page with the given content.
     *
     * @param html the whole page, as HTML
     */
    StartupPage(final String html)
    {
        this.html = html;
    }

    /**
     * Reads the page this bundle ships.
     *
     * @return the bundled startup page
     * @throws IOException if the page cannot be read from the bundle, which means a broken build
     */
    static StartupPage bundled() throws IOException
    {
        return fromResource(BUNDLED_PAGE);
    }

    /**
     * Reads a page from the classpath.
     *
     * @param path the absolute classpath path of an HTML file encoded in UTF-8, such as {@code /custom_index.html}
     * @return the page in that file
     * @throws IOException if the file cannot be read
     * @throws NullPointerException if there is no such file on the classpath
     */
    static StartupPage fromResource(final String path) throws IOException
    {
        final URL location = Objects.requireNonNull(StartupPage.class.getResource(path), "No startup page at " + path);
        try (InputStream page = location.openStream()) {
            return new StartupPage(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(page.readAllBytes())).toString());
        }
    }

    /**
     * Answers a request with this page and a 503 status.
     *
     * @param response the HTTP response to write into
     * @throws IOException if the page cannot be written to the response
     */
    // The startup page is HTML
    @SuppressWarnings("checkstyle:directResponseWriter")
    void serve(final HttpServletResponse response) throws IOException
    {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("text/html;charset=UTF-8");
        // The real page replaces the stub at this URL once the gate opens, so the browser must not keep the stub
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(this.html);
    }
}
