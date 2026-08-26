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
 * Lets a caller pass serialization selectors as {@code selector} query parameters instead of writing them into the
 * path, by recording them for the duration of the request so that every parse during it includes them.
 *
 * <p>
 * The need is narrow but real: selectors are separated by dots, so a selector carrying a dot in its own value has
 * to escape it with a backslash — and Jetty refuses a backslash in a path, encoded or not, unless the deployment
 * loosens its URI compliance for every request it will ever serve. A query string has no such restriction, so
 * {@code ?selector=dataOption:formSelectors=-dereference.simple.deep} says what the path cannot. The parameter may
 * be repeated, one whole selector per occurrence, and because nothing splits it there is nothing to escape.
 * </p>
 *
 * <p>
 * <b>Why it is recorded rather than written somewhere.</b> Nothing that reads selectors has the request: they all
 * work from a resource, and one of them re-resolves the path info to serialize the same resource a second way, a
 * pass with no request anywhere near it. Both places a request-scoped value could have been put were tried and
 * measured to fail — a resource's own metadata is locked ({@code JcrNodeResourceMetadata is locked}), and a
 * {@code ResourceWrapper} carrying different metadata is invisible to the serializer, because
 * {@code ResourceWrapper.adaptTo} delegates to the resource it wraps and the serializer is reached through
 * {@code adaptTo}. What is left, and what {@link SelectorUtils#setRequestSelectors} implements, is to hand them to
 * the parser directly.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Filter.class,
    property = {
        // Ahead of any filter that reads selectors itself, so that all of them see the same ones
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
        final String[] extras = request instanceof SlingJakartaHttpServletRequest slingRequest
            ? slingRequest.getParameterValues(SELECTOR_PARAMETER) : null;
        if (extras == null || extras.length == 0) {
            chain.doFilter(request, response);
            return;
        }
        try {
            SelectorUtils.setRequestSelectors(List.of(extras));
            chain.doFilter(request, response);
        } finally {
            // Sling serves a request on a pooled thread, so leaving these behind would apply them to whatever it
            // serves next
            SelectorUtils.clearRequestSelectors();
        }
    }

    @Override
    public void destroy()
    {
        // Nothing to do
    }
}
