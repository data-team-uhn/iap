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

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

/**
 * Serves every file stored in the repository by its users as a download, never as a page. Sling streams a stored
 * file back with the type it was stored under, and the browser renders it in IAP's own origin. An SVG or an HTML
 * file holding a script would then run with the session of whoever opened it.
 *
 * <p>Refusing such types when they are stored is not enough. The uploader names the type, and Sling serves a file
 * stored as {@code application/octet-stream} with the type its name suggests, so {@code drawing.svg} is an SVG
 * whatever it was stored as.</p>
 *
 * <p>The response carries {@code Content-Disposition: attachment}, so the browser saves the file instead of
 * opening it, and {@code Content-Security-Policy: sandbox}, so that if it is opened anyway, it runs in an origin of
 * its own. Neither changes how a page loads a file as an image, a script or a stylesheet.</p>
 *
 * <p>The files the application ships, under {@code /libs} and {@code /apps}, are served as they are. Nobody but an
 * administrator can write there.</p>
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
public class StoredFileDownloadFilter implements Filter
{
    /** Where the application's own files are. */
    private static final List<String> SHIPPED_PATHS = List.of("/libs/", "/apps/");

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
            && response instanceof final HttpServletResponse httpResponse
            && isStoredFile(slingRequest.getResource())) {
            httpResponse.setHeader("Content-Disposition", "attachment");
            httpResponse.setHeader("Content-Security-Policy", "sandbox");
        }
        chain.doFilter(request, response);
    }

    /**
     * Whether the resource is content a user stored, which Sling would stream back as a file.
     *
     * @param resource the requested resource
     * @return {@code true} for a property, or a node holding data, outside the application's own files
     */
    private static boolean isStoredFile(final Resource resource)
    {
        if (SHIPPED_PATHS.stream().anyMatch(resource.getPath()::startsWith)) {
            return false;
        }
        // Sling streams any single property's value
        if (resource.adaptTo(Property.class) != null) {
            return true;
        }
        final Node node = resource.adaptTo(Node.class);
        return node != null && holdsData(node);
    }

    /**
     * Whether Sling would stream the node's content: the shapes its stream renderer reads data from.
     *
     * @param node the requested node
     * @return {@code true} when the node, or its {@code jcr:content}, has a {@code jcr:data}, or the node links to
     *         another file's content
     */
    private static boolean holdsData(final Node node)
    {
        try {
            return node.hasProperty("jcr:data") || node.hasProperty("jcr:content/jcr:data")
                || node.isNodeType("nt:linkedFile");
        } catch (final RepositoryException e) {
            // A node that cannot be read is not served either, so treating it as a file costs nothing
            return true;
        }
    }

    @Override
    public void destroy()
    {
        // Nothing to do
    }
}
