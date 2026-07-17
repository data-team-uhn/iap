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

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link PreventVersionOverrideServletFilter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class PreventVersionOverrideServletFilterTest
{
    private static final String BASE_VERSION_PARAM = ":baseVersion";

    private static final String CURRENT_VERSION_PATH = "/jcr:system/jcr:versionStorage/current";

    private PreventVersionOverrideServletFilter filter;

    private FilterChain chain;

    private ServletResponse response;

    @BeforeEach
    public void setup()
    {
        this.filter = new PreventVersionOverrideServletFilter();
        this.chain = Mockito.mock(FilterChain.class);
        this.response = Mockito.mock(ServletResponse.class);
    }

    @Test
    public void testRequestWithoutBaseVersionPassesThrough()
        throws Exception
    {
        final SlingJakartaHttpServletRequest request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        Mockito.when(request.getParameter(BASE_VERSION_PARAM)).thenReturn(null);

        this.filter.doFilter(request, this.response, this.chain);

        Mockito.verify(this.chain).doFilter(request, this.response);
    }

    @Test
    public void testNonSlingRequestPassesThrough()
        throws Exception
    {
        final ServletRequest request = Mockito.mock(ServletRequest.class);
        Mockito.when(request.getParameter(BASE_VERSION_PARAM)).thenReturn("v1");

        this.filter.doFilter(request, this.response, this.chain);

        Mockito.verify(this.chain).doFilter(request, this.response);
    }

    @Test
    public void testMatchingBaseVersionPassesThrough()
        throws Exception
    {
        final SlingJakartaHttpServletRequest request = mockRequestWithNodeVersion(CURRENT_VERSION_PATH);
        Mockito.when(request.getParameter(BASE_VERSION_PARAM)).thenReturn(CURRENT_VERSION_PATH);

        this.filter.doFilter(request, this.response, this.chain);

        Mockito.verify(this.chain).doFilter(request, this.response);
    }

    @Test
    public void testMismatchedBaseVersionIsRejected()
        throws Exception
    {
        final SlingJakartaHttpServletRequest request = mockRequestWithNodeVersion(CURRENT_VERSION_PATH);
        Mockito.when(request.getParameter(BASE_VERSION_PARAM)).thenReturn("/some/stale/version");

        Assertions.assertThrows(ServletException.class,
            () -> this.filter.doFilter(request, this.response, this.chain));

        Mockito.verify(request).setAttribute("jakarta.servlet.error.status_code", 409);
        Mockito.verify(this.chain, Mockito.never()).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    public void testNullNodePassesThrough()
        throws Exception
    {
        final SlingJakartaHttpServletRequest request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        Mockito.when(request.getParameter(BASE_VERSION_PARAM)).thenReturn("v1");
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.adaptTo(Node.class)).thenReturn(null);
        Mockito.when(request.getResource()).thenReturn(resource);

        this.filter.doFilter(request, this.response, this.chain);

        Mockito.verify(this.chain).doFilter(request, this.response);
    }

    @Test
    public void testRepositoryExceptionIsHandledGracefully()
        throws Exception
    {
        final SlingJakartaHttpServletRequest request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        Mockito.when(request.getParameter(BASE_VERSION_PARAM)).thenReturn("v1");
        final Resource resource = Mockito.mock(Resource.class);
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getProperty("jcr:baseVersion")).thenThrow(new RepositoryException("boom"));
        Mockito.when(resource.adaptTo(Node.class)).thenReturn(node);
        Mockito.when(request.getResource()).thenReturn(resource);

        this.filter.doFilter(request, this.response, this.chain);

        // A repository failure is logged and the request is allowed to proceed
        Mockito.verify(this.chain).doFilter(request, this.response);
    }

    @Test
    public void testInitAndDestroyAreNoOps()
        throws Exception
    {
        Assertions.assertDoesNotThrow(() -> this.filter.init(null));
        Assertions.assertDoesNotThrow(() -> this.filter.destroy());
    }

    private SlingJakartaHttpServletRequest mockRequestWithNodeVersion(final String versionPath)
        throws RepositoryException
    {
        final SlingJakartaHttpServletRequest request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        final Resource resource = Mockito.mock(Resource.class);
        final Node node = Mockito.mock(Node.class);
        final Property property = Mockito.mock(Property.class);
        final Node versionNode = Mockito.mock(Node.class);
        Mockito.when(versionNode.getPath()).thenReturn(versionPath);
        Mockito.when(property.getNode()).thenReturn(versionNode);
        Mockito.when(node.getProperty("jcr:baseVersion")).thenReturn(property);
        Mockito.when(resource.adaptTo(Node.class)).thenReturn(node);
        Mockito.when(request.getResource()).thenReturn(resource);
        return request;
    }
}
