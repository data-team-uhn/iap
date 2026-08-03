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
package io.uhndata.iap.healthcheck.internal;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.felix.hc.api.Result;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.engine.SlingRequestProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LoginPageReadyCheck}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class LoginPageReadyCheckTest
{
    private ResourceResolverFactory resolverFactory;

    private ResourceResolver resolver;

    private SlingRequestProcessor processor;

    private LoginPageReadyCheck check;

    @BeforeEach
    void setUp() throws LoginException
    {
        this.resolverFactory = mock(ResourceResolverFactory.class);
        this.resolver = mock(ResourceResolver.class);
        when(this.resolverFactory.getServiceResourceResolver(Mockito.anyMap())).thenReturn(this.resolver);
        this.processor = mock(SlingRequestProcessor.class);
        this.check = new LoginPageReadyCheck(this.resolverFactory, this.processor);
    }

    private void respondWith(final int status) throws IOException
    {
        doAnswer(invocation -> {
            invocation.getArgument(1, HttpServletResponse.class).setStatus(status);
            return null;
        }).when(this.processor).processRequest(any(jakarta.servlet.http.HttpServletRequest.class),
            any(HttpServletResponse.class), any(ResourceResolver.class));
    }

    @Test
    void reportsOkWhenTheLoginPageRenders() throws Exception
    {
        respondWith(HttpServletResponse.SC_OK);

        final Result result = this.check.execute();

        assertTrue(result.isOk());
    }

    @Test
    void rendersInternallyAndReleasesTheResolver() throws Exception
    {
        respondWith(HttpServletResponse.SC_OK);

        this.check.execute();

        verify(this.processor).processRequest(any(jakarta.servlet.http.HttpServletRequest.class),
            any(HttpServletResponse.class), Mockito.eq(this.resolver));
        verify(this.resolver).close();
    }

    @Test
    void reportsTemporarilyUnavailableWhenTheLoginPageErrorsOut() throws IOException
    {
        respondWith(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

        final Result result = this.check.execute();

        assertFalse(result.isOk());
        assertEquals(Result.Status.TEMPORARILY_UNAVAILABLE, result.getStatus());
    }

    @Test
    void reportsTemporarilyUnavailableWhenRenderingThrows() throws IOException
    {
        doAnswer(invocation -> {
            throw new IllegalStateException("scripting engine not ready");
        }).when(this.processor).processRequest(any(jakarta.servlet.http.HttpServletRequest.class),
            any(HttpServletResponse.class), any(ResourceResolver.class));

        final Result result = this.check.execute();

        assertFalse(result.isOk());
        assertEquals(Result.Status.TEMPORARILY_UNAVAILABLE, result.getStatus());
    }

    @Test
    void reportsTemporarilyUnavailableWhenTheServiceUserIsNotAvailable() throws LoginException
    {
        when(this.resolverFactory.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no mapping yet"));

        final Result result = this.check.execute();

        assertFalse(result.isOk());
        assertEquals(Result.Status.TEMPORARILY_UNAVAILABLE, result.getStatus());
    }
}
