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
package io.uhndata.iap.favicon.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;

/**
 * Answers {@code /favicon.ico} with the vector icon when the client says it can draw one.
 *
 * <p>
 * Browsers probe {@code /favicon.ico} by convention, whatever the page declares, and a client that asks for
 * {@code image/svg+xml} by name is better served by the crisp, dark-scheme-aware {@code /favicon.svg} than by the
 * fixed raster sizes. The two files are stored as they are generated; this servlet only picks one per request, and
 * says so in {@code Vary: Accept} so that a cache does not hand one client's answer to another.
 * </p>
 *
 * <p>
 * It is bound by resource type, which the {@code favicon.ico} node carries, rather than mounted at the path: a
 * path-mounted servlet would shadow the node, and with it the access control that makes the icon readable before
 * login. Only an explicit {@code image/svg+xml} counts; a wildcard is what every client sends and says nothing
 * about vector support.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    resourceTypes = FaviconServlet.RESOURCE_TYPE,
    methods = { HttpConstants.METHOD_GET, HttpConstants.METHOD_HEAD })
public class FaviconServlet extends SlingJakartaSafeMethodsServlet
{
    /** The resource type the {@code favicon.ico} node declares. */
    static final String RESOURCE_TYPE = "iap/Favicon";

    static final String SVG_PATH = "/favicon.svg";

    static final String SVG_TYPE = "image/svg+xml";

    static final String ICO_TYPE = "image/x-icon";

    private static final long serialVersionUID = 7390163455819262731L;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        response.setHeader("Vary", "Accept");
        final Resource vector = acceptsSvg(request.getHeader("Accept"))
            ? request.getResourceResolver().getResource(SVG_PATH)
            : null;
        // A missing vector icon is not worth failing the request over: the raster one is always there
        final boolean sendVector = vector != null;
        final Resource source = sendVector ? vector : request.getResource();
        final byte[] bytes = readContent(source);
        if (bytes == null) {
            response.sendError(SlingJakartaHttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType(sendVector ? SVG_TYPE : ICO_TYPE);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    private static byte[] readContent(final Resource source) throws IOException
    {
        try (InputStream content = source.adaptTo(InputStream.class)) {
            return content == null ? null : content.readAllBytes();
        }
    }

    /**
     * Whether an {@code Accept} header names {@code image/svg+xml} and does not refuse it with {@code q=0}.
     *
     * @param accept the header's value, may be {@code null}
     * @return {@code true} if the vector icon should be sent
     */
    static boolean acceptsSvg(final String accept)
    {
        return accept != null && Arrays.stream(accept.split(","))
            .map(range -> range.split(";"))
            .filter(parts -> SVG_TYPE.equalsIgnoreCase(parts[0].trim()))
            .anyMatch(parts -> Arrays.stream(parts).skip(1).noneMatch(FaviconServlet::refuses));
    }

    private static boolean refuses(final String parameter)
    {
        final String[] pair = parameter.trim().split("=", 2);
        if (pair.length != 2 || !"q".equalsIgnoreCase(pair[0].trim())) {
            return false;
        }
        try {
            return Double.parseDouble(pair[1].trim()) <= 0;
        } catch (final NumberFormatException e) {
            return false;
        }
    }
}
