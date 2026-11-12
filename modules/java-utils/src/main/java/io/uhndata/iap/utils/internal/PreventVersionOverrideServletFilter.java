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

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Refuses a {@code POST} or {@code PUT} whose {@code :baseVersion} parameter is not the path of the resource's
 * current base version. The resource was then checked in again after the client read it. The filter records
 * {@code 409} as the error's status code and throws. If the resource is not a versionable node, the request goes
 * ahead and the parameter is ignored. The request also goes ahead when the base version cannot be read.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Filter.class,
    property = {
        "service.ranking:Integer=0",
        "sling.filter.scope=REQUEST",
        "sling.filter.methods=POST",
        "sling.filter.methods=PUT"
    })
public class PreventVersionOverrideServletFilter implements Filter
{
    private static final Logger LOGGER = LoggerFactory.getLogger(PreventVersionOverrideServletFilter.class);

    @Override
    public void init(final FilterConfig filterConfig) throws ServletException
    {
        // Nothing to do
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
        throws IOException, ServletException
    {
        String requestBaseVersion = request.getParameter(":baseVersion");
        if (!(request instanceof SlingJakartaHttpServletRequest) || requestBaseVersion == null) {
            chain.doFilter(request, response);
            return;
        }
        SlingJakartaHttpServletRequest slingRequest = (SlingJakartaHttpServletRequest) request;
        try {
            if (isOutdated(slingRequest.getResource().adaptTo(Node.class), requestBaseVersion)) {
                slingRequest.setAttribute("jakarta.servlet.error.status_code", HttpServletResponse.SC_CONFLICT);
                throw new ServletException("This content changed after you opened it. "
                    + "Reload it to see the latest version.");
            }
        } catch (RepositoryException e) {
            LOGGER.warn("Failed to determine current resource version: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(PreventVersionOverrideServletFilter.class, "checkVersion")
                .about(slingRequest.getResource().getPath()));
        }
        chain.doFilter(request, response);
    }

    private static boolean isOutdated(final Node node, final String requestBaseVersion)
        throws RepositoryException
    {
        return node != null && node.isNodeType(NodeType.MIX_VERSIONABLE)
            && !requestBaseVersion.equals(node.getProperty("jcr:baseVersion").getNode().getPath());
    }

    @Override
    public void destroy()
    {
        // Nothing to do
    }
}
