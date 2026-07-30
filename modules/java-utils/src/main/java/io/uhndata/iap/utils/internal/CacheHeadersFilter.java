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
 * Sets an explicit {@code Cache-Control} policy on every read response, so that browsers never have to guess.
 * Without it, responses carrying a {@code Last-Modified} but no caching directives are cached heuristically,
 * each URL expiring on its own schedule - making a freshly deployed frontend visible on some pages and stale on
 * others, since the (mutable, stable-URL) HTML pages and asset manifest may keep pointing at a previous build.
 *
 * <p>
 * The policy is simple: the content-hashed frontend files under {@code /libs/iap/resources/} can never change
 * without also changing their URL, so they are cacheable forever; everything else - pages, the asset manifest,
 * data - must be revalidated on every use, which still allows cheap 304 responses where validators exist.
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
    /** The webpack build outputs under this path are the candidates for indefinite caching. */
    private static final String FRONTEND_RESOURCES_PATH = "/libs/iap/resources/";

    /**
     * A content-hashed file name: some segment of it is a long hexadecimal content hash, e.g.
     * {@code runtime.f6d1b7fad82256cf4412.js} or {@code vendor.0f3a[...].js.LICENSE.txt}. The asset manifest
     * ({@code assets.json}) and other non-hashed names deliberately do not match.
     */
    private static final Pattern CONTENT_HASHED = Pattern.compile(".*\\.[0-9a-f]{10,}\\..*");

    /** Immutable content: cache for a year, and never even revalidate. */
    private static final String CACHE_FOREVER = "public, max-age=31536000, immutable";

    /** Mutable content: cache, but revalidate before every use. */
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
        if (request instanceof SlingJakartaHttpServletRequest slingRequest
            && response instanceof HttpServletResponse httpResponse) {
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
