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
package io.uhndata.iap.startup.internal;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.felix.hc.api.Result;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StartupGateFilter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class StartupGateFilterTest
{
    private static final String LOGIN_CHECK = "Login Page Ready Check";

    private HealthCheckExecutor executor;

    private StartupGateFilter filter;

    private ServletRequest request;

    private HttpServletResponse response;

    private FilterChain chain;

    private StringWriter body;

    @BeforeEach
    void setUp() throws IOException
    {
        this.executor = mock(HealthCheckExecutor.class);
        this.filter = new StartupGateFilter(this.executor);
        this.request = mock(ServletRequest.class);
        this.response = mock(HttpServletResponse.class);
        this.chain = mock(FilterChain.class);
        this.body = new StringWriter();
        when(this.response.getWriter()).thenReturn(new PrintWriter(this.body));
    }

    private HealthCheckExecutionResult result(final String name, final boolean ok)
    {
        final HealthCheckExecutionResult result = mock(HealthCheckExecutionResult.class);
        final HealthCheckMetadata metadata = mock(HealthCheckMetadata.class);
        when(metadata.getName()).thenReturn(name);
        when(result.getHealthCheckMetadata()).thenReturn(metadata);
        when(result.getHealthCheckResult())
            .thenReturn(new Result(ok ? Result.Status.OK : Result.Status.TEMPORARILY_UNAVAILABLE, name));
        return result;
    }

    @Test
    void passesRequestsThroughWhenAllChecksPassIncludingTheLoginPageCheck() throws Exception
    {
        final List<HealthCheckExecutionResult> results =
            List.of(result("OSGi Framework Ready Check", true), result(LOGIN_CHECK, true));
        when(this.executor.execute(any())).thenReturn(results);

        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain).doFilter(this.request, this.response);
        verify(this.response, never()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void servesTheStartupPageWhenAnyCheckFails() throws Exception
    {
        final List<HealthCheckExecutionResult> results =
            List.of(result("OSGi Framework Ready Check", false), result(LOGIN_CHECK, true));
        when(this.executor.execute(any())).thenReturn(results);

        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain, never()).doFilter(this.request, this.response);
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(this.response).setHeader("Cache-Control", "no-store");
        assertTrue(this.body.toString().contains("IAP is starting up"));
    }

    @Test
    void servesTheStartupPageWhenTheLoginPageCheckIsMissing() throws Exception
    {
        // Every present check passes, but the one that proves the login page renders is not registered
        // (yet, or anymore): the gate must fail closed rather than trust an incomplete picture.
        final List<HealthCheckExecutionResult> results = List.of(result("OSGi Framework Ready Check", true));
        when(this.executor.execute(any())).thenReturn(results);

        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain, never()).doFilter(this.request, this.response);
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void servesTheStartupPageWhenNoChecksAreRegistered() throws Exception
    {
        when(this.executor.execute(any())).thenReturn(List.of());

        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain, never()).doFilter(this.request, this.response);
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertTrue(this.body.toString().contains("IAP is starting up"));
    }

    @Test
    void servesTheStartupPageWhenTheLoginPageCheckFails() throws Exception
    {
        final List<HealthCheckExecutionResult> results = List.of(result(LOGIN_CHECK, false));
        when(this.executor.execute(any())).thenReturn(results);

        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain, never()).doFilter(this.request, this.response);
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void doFilterIsQuietUnderRepeatedCalls() throws Exception
    {
        final List<HealthCheckExecutionResult> results = List.of(result(LOGIN_CHECK, true));
        when(this.executor.execute(any())).thenReturn(results);

        this.filter.doFilter(this.request, this.response, this.chain);
        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain, Mockito.times(2)).doFilter(this.request, this.response);
    }
}
