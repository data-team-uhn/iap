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
package io.uhndata.iap.deletion.internal;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

/**
 * Refuses every deletion that does not go through a workflow: an HTTP {@code DELETE}, and the Sling POST servlet's
 * {@code delete} operation. The operation is refused whatever it names with {@code :applyTo}. Content is deleted by a
 * workflow's {@code delete} event, which asks the deletion guards and records who deleted what.
 *
 * <p>An instance started with the framework property {@code iap.deletion.direct=allowed} lets such requests through.
 * The test data and the integration tests set it, to remove what they create. Nothing a real instance runs sets it,
 * so leaving it out refuses.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Filter.class,
    property = {
        "service.ranking:Integer=0",
        "sling.filter.scope=REQUEST",
        "sling.filter.methods=DELETE",
        "sling.filter.methods=POST"
    })
public class DirectDeletionFilter implements Filter
{
    /** The framework property that lets direct deletions through when it says {@link #ALLOWED_VALUE}. */
    static final String SWITCH_PROPERTY = "iap.deletion.direct";

    /** The value of {@link #SWITCH_PROPERTY} that lets direct deletions through. */
    static final String ALLOWED_VALUE = "allowed";

    private static final String REFUSAL_MESSAGE =
        "Content is deleted by its workflow: send it a delete event, as POST <path>.delete.json";

    private final boolean allowed;

    /**
     * Reads whether this instance lets direct deletions through.
     *
     * @param bundleContext gives the framework properties
     */
    @Activate
    public DirectDeletionFilter(final BundleContext bundleContext)
    {
        this.allowed = ALLOWED_VALUE.equals(bundleContext.getProperty(SWITCH_PROPERTY));
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
        throws IOException, ServletException
    {
        if (!this.allowed && isDirectDeletion((HttpServletRequest) request)) {
            DeletionResponses.send((SlingJakartaHttpServletResponse) response,
                HttpServletResponse.SC_METHOD_NOT_ALLOWED, "refused", REFUSAL_MESSAGE);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isDirectDeletion(final HttpServletRequest request)
    {
        return "DELETE".equals(request.getMethod()) || "delete".equals(request.getParameter(":operation"));
    }
}
