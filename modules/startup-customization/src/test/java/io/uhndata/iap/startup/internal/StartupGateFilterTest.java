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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.felix.hc.api.Result;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.component.ComponentContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private static final String OTHER_CHECK = "OSGi Framework Ready Check";

    private HealthCheckExecutor executor;

    private ComponentContext componentContext;

    private ScheduledExecutorService poller;

    private StartupGateFilter filter;

    private HttpServletRequest request;

    private HttpServletResponse response;

    private FilterChain chain;

    private StringWriter body;

    @BeforeEach
    void setUp() throws IOException
    {
        this.executor = mock(HealthCheckExecutor.class);
        this.componentContext = mock(ComponentContext.class);
        this.poller = mock(ScheduledExecutorService.class);
        this.filter = new StartupGateFilter(this.executor, this.componentContext, this.poller);
        this.request = mock(HttpServletRequest.class);
        when(this.request.getRequestURI()).thenReturn("/some/page.html");
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

    /** Nested stubbing confuses Mockito, so the results are always built before the executor is stubbed. */
    private void checksReport(final HealthCheckExecutionResult... results)
    {
        final List<HealthCheckExecutionResult> reported = List.of(results);
        when(this.executor.execute(any())).thenReturn(reported);
    }

    private void assertStillGated()
    {
        verify(this.componentContext, never()).disableComponent(anyString());
    }

    @Test
    void schedulesTheReadinessPoller()
    {
        verify(this.poller).scheduleWithFixedDelay(any(Runnable.class), eq(StartupGateFilter.POLL_INTERVAL_MS),
            eq(StartupGateFilter.POLL_INTERVAL_MS), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void staysClosedBeforeTheFirstPoll() throws Exception
    {
        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain, never()).doFilter(this.request, this.response);
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(this.response).setHeader("Cache-Control", "no-store");
        assertEquals(StartupPageFixture.stubPage(), this.body.toString());
    }

    @Test
    void letsSystemRequestsThroughWhileStillClosed() throws Exception
    {
        // The console and the health checks are how a stuck startup gets diagnosed, so they are never gated.
        // The whiteboard matches its own pattern against context-relative paths, and the console lives in a
        // context of its own, so this cannot be left to the filter.regex property.
        checksReport();
        when(this.request.getRequestURI()).thenReturn("/system/console/bundles.json");

        this.filter.poll(0);
        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain).doFilter(this.request, this.response);
        verify(this.response, never()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void opensWhenAllChecksPassIncludingTheLoginPageCheck() throws Exception
    {
        checksReport(result(OTHER_CHECK, true), result(LOGIN_CHECK, true));

        this.filter.poll(0);
        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain).doFilter(this.request, this.response);
        verify(this.response, never()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void staysClosedWhenAnyCheckFails() throws Exception
    {
        checksReport(result(OTHER_CHECK, false), result(LOGIN_CHECK, true));

        this.filter.poll(0);
        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain, never()).doFilter(this.request, this.response);
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertEquals(StartupPageFixture.stubPage(), this.body.toString());
    }

    @Test
    void staysClosedWhenTheLoginPageCheckIsMissing() throws Exception
    {
        // Every present check passes, but the one that proves the login page renders is not registered
        // (yet, or anymore): the gate must fail closed rather than trust an incomplete picture.
        checksReport(result(OTHER_CHECK, true));

        this.filter.poll(0);
        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain, never()).doFilter(this.request, this.response);
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void staysClosedWhenNoChecksAreRegistered() throws Exception
    {
        checksReport();

        this.filter.poll(0);
        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain, never()).doFilter(this.request, this.response);
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void closesAgainWhenReadinessRegresses() throws Exception
    {
        checksReport(result(LOGIN_CHECK, true));
        this.filter.poll(0);

        checksReport(result(LOGIN_CHECK, false));
        this.filter.poll(StartupGateFilter.SETTLE_NANOS);
        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain, never()).doFilter(this.request, this.response);
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertStillGated();
    }

    @Test
    void doesNotRetireBeforeReadinessHasSettled()
    {
        checksReport(result(LOGIN_CHECK, true));

        this.filter.poll(0);
        this.filter.poll(StartupGateFilter.SETTLE_NANOS - 1);

        assertStillGated();
    }

    @Test
    void retiresTheWholeGateOnceReadinessHasSettled()
    {
        checksReport(result(OTHER_CHECK, true), result(LOGIN_CHECK, true));

        this.filter.poll(0);
        this.filter.poll(StartupGateFilter.SETTLE_NANOS);

        verify(this.componentContext).disableComponent(StartupPlaceholderServlet.class.getName());
        verify(this.componentContext).disableComponent(StartupPlaceholderContext.class.getName());
        verify(this.componentContext).disableComponent(StartupGateFilter.class.getName());
        verify(this.poller).shutdown();
    }

    @Test
    void restartsTheSettleWindowAfterAnInterruption()
    {
        // Ready, briefly not ready, ready again: only the last stretch counts, so a pair of ready polls
        // straddling the interruption must not be mistaken for a system that has been up all along.
        checksReport(result(LOGIN_CHECK, true));
        this.filter.poll(0);

        checksReport(result(LOGIN_CHECK, false));
        this.filter.poll(1);

        checksReport(result(LOGIN_CHECK, true));
        this.filter.poll(2);
        this.filter.poll(StartupGateFilter.SETTLE_NANOS);

        assertStillGated();

        this.filter.poll(StartupGateFilter.SETTLE_NANOS + 2);

        verify(this.componentContext).disableComponent(StartupGateFilter.class.getName());
    }

    @Test
    void pollsWithTheCurrentTime() throws Exception
    {
        checksReport(result(LOGIN_CHECK, true));

        this.filter.poll();
        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain).doFilter(this.request, this.response);
        assertStillGated();
    }

    @Test
    void stopsPollingWhenDeactivated()
    {
        this.filter.deactivate();

        verify(this.poller).shutdownNow();
    }

    @Test
    void pollsOnItsOwnScheduleWhenActivatedByOsgi() throws Exception
    {
        checksReport(result(LOGIN_CHECK, true));
        final AtomicBoolean passedThrough = new AtomicBoolean();
        final FilterChain recordingChain = (req, res) -> passedThrough.set(true);
        // The real scheduler is in charge here: nothing below calls poll(), so the gate can only open if the
        // component scheduled its own readiness evaluations.
        final StartupGateFilter osgiFilter = new StartupGateFilter(this.executor, this.componentContext);

        try {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!passedThrough.get() && System.nanoTime() < deadline) {
                osgiFilter.doFilter(this.request, this.response, recordingChain);
                Thread.sleep(50);
            }
        } finally {
            osgiFilter.deactivate();
        }

        assertTrue(passedThrough.get());
    }

    @Test
    void pollsOnADaemonThread() throws Exception
    {
        final ScheduledExecutorService realPoller = StartupGateFilter.newPoller();
        try {
            final CountDownLatch started = new CountDownLatch(1);
            realPoller.execute(started::countDown);

            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertTrue(realPoller.submit(() -> Thread.currentThread().isDaemon()).get());
        } finally {
            realPoller.shutdownNow();
        }
    }
}
