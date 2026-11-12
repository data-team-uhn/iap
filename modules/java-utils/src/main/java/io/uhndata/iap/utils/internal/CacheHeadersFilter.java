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
package io.uhndata.iap.utils.internal;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.osgi.service.component.annotations.Component;

/**
 * Sets a {@code Cache-Control} header on every {@code GET} and {@code HEAD} response. A browser caches a response
 * that has a {@code Last-Modified} header and no {@code Cache-Control} for as long as it guesses. A page could then
 * keep pointing at the frontend files of an earlier build.
 *
 * <p>
 * A content-hashed file under {@code /libs/iap/resources/} changes its URL whenever it changes, so it is cached for
 * a year without revalidation. Everything else, including pages and the asset manifest, is revalidated on every use,
 * and can still be answered with a {@code 304}.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Filter.class,
    property = {
        "service.ranking:Integer=0",
        "sling.filter.scope=REQUEST",
        "sling.filter.methods=GET",
        "sling.filter.methods=HEAD"
    })
public class CacheHeadersFilter implements Filter
{
    /** Where the frontend build's files are, the only ones cached without revalidation. */
    private static final String FRONTEND_RESOURCES_PATH = "/libs/iap/resources/";

    /**
     * A content-hashed file name: it has ten or more hexadecimal digits between two dots, such as
     * {@code runtime.f6d1b7fad82256cf4412.js} or {@code vendor.0f3a[...].js.LICENSE.txt}. The asset manifest,
     * {@code assets.json}, does not match.
     */
    private static final Pattern CONTENT_HASHED = Pattern.compile(".*\\.[0-9a-f]{10,}\\..*");

    /** For a file that never changes: cache it for a year, and never revalidate it. */
    private static final String CACHE_FOREVER = "public, max-age=31536000, immutable";

    /** For anything that can change: cache it, but revalidate it before every use. */
    private static final String ALWAYS_REVALIDATE = "no-cache";

    @Override
    public void init(final FilterConfig filterConfig) throws ServletException
    {
        // Nothing to do
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
        throws IOException, ServletException
    {
        if (request instanceof final SlingJakartaHttpServletRequest slingRequest
            && response instanceof final HttpServletResponse httpResponse) {
            httpResponse.setHeader("Cache-Control", policyFor(slingRequest.getResource().getPath()));
        }
        chain.doFilter(request, response);
    }

    private String policyFor(final String path)
    {
        return path.startsWith(FRONTEND_RESOURCES_PATH)
            && CONTENT_HASHED.matcher(path.substring(FRONTEND_RESOURCES_PATH.length())).matches()
                ? CACHE_FOREVER : ALWAYS_REVALIDATE;
    }

    @Override
    public void destroy()
    {
        // Nothing to do
    }
}
