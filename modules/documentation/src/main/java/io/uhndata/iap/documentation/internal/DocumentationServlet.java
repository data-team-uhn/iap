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
package io.uhndata.iap.documentation.internal;

import java.io.IOException;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.JakartaOptingServlet;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.documentation.api.SelfDocumenting;

/**
 * Serves the documentation of any {@link SelfDocumenting self-documenting} node: appending {@code .doc.json} or
 * {@code .doc.md} to the path of a node marked with the {@code iap:Documented} mixin returns its catalogue as JSON
 * or as a human-readable Markdown document. The servlet is not tied to any particular resource type: it opts in for
 * the nodes carrying the mixin, whether set explicitly or inherited from their primary type, and renders whatever
 * their {@link SelfDocumenting} model reports; a feature needing a completely different rendering can still
 * register its own servlet for the same selector on its resource type, which takes precedence over this one.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { "sling/servlet/default" }, methods = { "GET" },
    selectors = { DocumentationServlet.SELECTOR }, extensions = { "json", "md" })
public class DocumentationServlet extends SlingJakartaSafeMethodsServlet implements JakartaOptingServlet
{
    /** The selector under which the documentation is served. */
    public static final String SELECTOR = "doc";

    /** The mixin marking the nodes that serve documentation. */
    public static final String MIXIN = "iap:Documented";

    private static final long serialVersionUID = -5476186011291043672L;

    @Override
    public boolean accepts(final SlingJakartaHttpServletRequest request)
    {
        final Node node = request.getResource().adaptTo(Node.class);
        try {
            // isNodeType sees the mixin both when set explicitly on the node
            // and when inherited from a supertype of the node's primary type
            return node != null && node.isNodeType(MIXIN);
        } catch (final RepositoryException e) {
            return false;
        }
    }

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        response.setCharacterEncoding("UTF-8");
        final SelfDocumenting documentation = request.getResource().adaptTo(SelfDocumenting.class);
        if (documentation == null) {
            // The node is marked as documented, but nothing provides its documentation
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("text/plain");
            response.getWriter().print("No documentation available for " + request.getResource().getPath());
            return;
        }
        if ("md".equals(request.getRequestPathInfo().getExtension())) {
            response.setContentType("text/markdown");
            response.getWriter().print(documentation.toMarkdown());
        } else {
            response.setContentType("application/json");
            response.getWriter().print(documentation.toDocumentationJson().toString());
        }
    }
}
