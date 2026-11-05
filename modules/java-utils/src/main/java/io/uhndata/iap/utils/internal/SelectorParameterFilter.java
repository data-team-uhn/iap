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
import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.utils.SelectorUtils;

/**
 * Lets a client give serialization selectors as {@code selector} query parameters instead of in the path. It hands
 * them to {@link SelectorUtils#setRequestSelectors} while the request runs, so that every parse includes them.
 *
 * <p>
 * A selector whose value has a dot must escape it with a backslash in the path. By default, Jetty refuses a
 * backslash in a path, encoded or not. A query string has no such rule:
 * {@code ?selector=dataOption:formSelectors=-dereference.simple.deep}. The parameter can be repeated, one whole
 * selector each time, and nothing in it is escaped.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Filter.class,
    property = {
        // Ahead of any filter that reads selectors, so that they all see the same ones
        "service.ranking:Integer=1000",
        "sling.filter.scope=REQUEST",
        "sling.filter.methods=GET",
        "sling.filter.methods=HEAD"
    })
public class SelectorParameterFilter implements Filter
{
    /** The query parameter carrying one extra selector; repeat it to add more. */
    static final String SELECTOR_PARAMETER = "selector";

    @Override
    public void init(final FilterConfig filterConfig) throws ServletException
    {
        // Nothing to do
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
        throws IOException, ServletException
    {
        final String[] extras = request instanceof final SlingJakartaHttpServletRequest slingRequest
            ? slingRequest.getParameterValues(SELECTOR_PARAMETER) : null;
        if (extras == null || extras.length == 0) {
            chain.doFilter(request, response);
            return;
        }
        try {
            SelectorUtils.setRequestSelectors(List.of(extras));
            chain.doFilter(request, response);
        } finally {
            // Jetty serves requests from a thread pool, and the thread's next request would get these
            SelectorUtils.clearRequestSelectors();
        }
    }

    @Override
    public void destroy()
    {
        // Nothing to do
    }
}
