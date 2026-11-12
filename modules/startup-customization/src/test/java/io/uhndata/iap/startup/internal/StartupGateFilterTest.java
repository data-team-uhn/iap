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
import java.util.stream.Stream;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.felix.hc.api.Result;
import org.apache.felix.hc.api.execution.HealthCheckExecutionOptions;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckMetadata;
import org.apache.felix.hc.api.execution.HealthCheckSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.osgi.framework.AllServiceListener;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.ServiceEvent;
import org.osgi.service.component.ComponentContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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

    private static final String FRAMEWORK_CHECK = "OSGi Framework Ready Check";

    private static final String BUNDLES_CHECK = "Bundles Started";

    private static final String CONTENT_CHECK = "Bundle Content Loaded";

    private static final String AUTH_CHECK = "Authentication Handler Ready Check";

    private static final String SERVICES_CHECK = "Services Ready Check";

    /**
     * The checks the gate requires, written out again rather than read from the filter, so a test fails when the
     * filter's list changes.
     */
    private static final List<String> REQUIRED =
        List.of(LOGIN_CHECK, FRAMEWORK_CHECK, BUNDLES_CHECK, AUTH_CHECK, SERVICES_CHECK);

    /** Stands in for the timings and stack traces that real checks put in their messages. */
    private static final String RESULT_MESSAGE = "diagnostics that must not be part of the description";

    private HealthCheckExecutor executor;

    private ComponentContext componentContext;

    private BundleContext bundleContext;

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
        this.bundleContext = mock(BundleContext.class);
        when(this.componentContext.getBundleContext()).thenReturn(this.bundleContext);
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
            .thenReturn(new Result(ok ? Result.Status.OK : Result.Status.TEMPORARILY_UNAVAILABLE, RESULT_MESSAGE));
        return result;
    }

    /**
     * Stubs what the executor reports. It uses {@code doReturn}, since {@code when} cannot re-stub an executor that
     * was made to throw. The results are built before the executor is stubbed, since Mockito fails on nested stubbing.
     */
    private void checksReport(final HealthCheckExecutionResult... results)
    {
        final List<HealthCheckExecutionResult> reported = List.of(results);
        doReturn(reported).when(this.executor).execute(any(), any());
    }

    private void checksReport(final List<String> names, final String failing)
    {
        checksReport(names.stream().map(name -> result(name, !name.equals(failing)))
            .toArray(HealthCheckExecutionResult[]::new));
    }

    /** Every required check, registered and passing, as on a started IAP. */
    private void everythingPasses()
    {
        checksReport(REQUIRED, null);
    }

    /** Every required check, registered, with only the given one failing. */
    private void everythingPassesExcept(final String failing)
    {
        checksReport(REQUIRED, failing);
    }

    /**
     * Sends a request through the gate without touching the shared response and chain mocks, so a test can call this
     * more than once.
     *
     * @return whether the request reached the application rather than the startup page
     */
    private boolean letsRequestsThrough() throws Exception
    {
        final AtomicBoolean passedThrough = new AtomicBoolean();
        final HttpServletResponse localResponse = mock(HttpServletResponse.class);
        when(localResponse.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        this.filter.doFilter(this.request, localResponse, (req, res) -> passedThrough.set(true));
        return passedThrough.get();
    }

    private void assertStillGated()
    {
        verify(this.componentContext, never()).disableComponent(anyString());
    }

    private void assertRetired()
    {
        verify(this.componentContext).disableComponent(StartupPlaceholderServlet.class.getName());
        verify(this.componentContext).disableComponent(StartupPlaceholderContext.class.getName());
        verify(this.componentContext).disableComponent(StartupGateFilter.class.getName());
        verify(this.poller).shutdown();
    }

    /** Rebuilds the gate as an end-to-end launch starts it, with {@link StartupGateFilter#RETIRE_WHEN_QUIET} set. */
    private void retiringWhenQuiet() throws IOException
    {
        when(this.bundleContext.getProperty(StartupGateFilter.RETIRE_WHEN_QUIET)).thenReturn("true");
        this.filter = new StartupGateFilter(this.executor, this.componentContext, this.poller);
    }

    private void aServiceChanges()
    {
        final ArgumentCaptor<AllServiceListener> listener = ArgumentCaptor.forClass(AllServiceListener.class);
        verify(this.bundleContext).addServiceListener(listener.capture());
        listener.getValue().serviceChanged(mock(ServiceEvent.class));
    }

    private void aBundleChanges()
    {
        final ArgumentCaptor<BundleListener> listener = ArgumentCaptor.forClass(BundleListener.class);
        verify(this.bundleContext).addBundleListener(listener.capture());
        listener.getValue().bundleChanged(mock(BundleEvent.class));
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
        // The console has a servlet context of its own, where the filter's regex sees only /bundles.json
        checksReport();
        when(this.request.getRequestURI()).thenReturn("/system/console/bundles.json");

        this.filter.poll(0);
        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain).doFilter(this.request, this.response);
        verify(this.response, never()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void opensOnlyWhenEveryRequiredCheckIsPresentAndPassingAndHasStayedThatWay() throws Exception
    {
        everythingPasses();

        this.filter.poll(0);
        assertFalse(letsRequestsThrough());

        this.filter.poll(StartupGateFilter.HOLD_NANOS);
        this.filter.doFilter(this.request, this.response, this.chain);

        assertNull(this.filter.findProblem());
        verify(this.chain).doFilter(this.request, this.response);
        verify(this.response, never()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    void aRegressionInsideTheHoldWindowStartsTheHoldAgain() throws Exception
    {
        everythingPasses();
        this.filter.poll(0);
        everythingPassesExcept(SERVICES_CHECK);
        this.filter.poll(TimeUnit.SECONDS.toNanos(2));
        everythingPasses();
        this.filter.poll(TimeUnit.SECONDS.toNanos(4));

        // Eight seconds after readiness first began, but only four after it came back
        this.filter.poll(TimeUnit.SECONDS.toNanos(8));
        assertFalse(letsRequestsThrough(), "readiness that did not last is not readiness");

        this.filter.poll(TimeUnit.SECONDS.toNanos(9));
        assertTrue(letsRequestsThrough());
    }

    @Test
    void staysClosedWhileAnyRequiredCheckIsMissing() throws Exception
    {
        for (final String absent : REQUIRED) {
            checksReport(REQUIRED.stream().filter(name -> !name.equals(absent)).toList(), null);

            this.filter.poll(0);

            final String problem = this.filter.findProblem();
            assertNotNull(problem, absent);
            assertTrue(problem.contains(absent), absent);
            assertFalse(letsRequestsThrough(), absent);
        }
        assertStillGated();
    }

    @Test
    void opensWithoutATaggedCheckThatIsNotOneOfTheRequiredOnes()
    {
        // No result for Bundle Content Loaded
        everythingPasses();

        assertNull(this.filter.findProblem());
    }

    @Test
    void staysClosedWhenATaggedCheckThatIsNotRequiredFails() throws Exception
    {
        checksReport(Stream.concat(REQUIRED.stream(), Stream.of(CONTENT_CHECK)).toList(), CONTENT_CHECK);

        this.filter.poll(0);

        assertTrue(this.filter.findProblem().contains(CONTENT_CHECK));
        assertFalse(letsRequestsThrough());
    }

    @Test
    void staysClosedWhenAnyCheckFails() throws Exception
    {
        everythingPassesExcept(FRAMEWORK_CHECK);

        this.filter.poll(0);
        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain, never()).doFilter(this.request, this.response);
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertEquals(StartupPageFixture.stubPage(), this.body.toString());
    }

    @Test
    void staysClosedWhenTheLoginPageCheckIsMissing() throws Exception
    {
        checksReport(REQUIRED.stream().filter(name -> !LOGIN_CHECK.equals(name)).toList(), null);

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
    void namesEveryRequiredCheckWhenTheyAllVanishAtOnce()
    {
        checksReport();

        final String problem = this.filter.findProblem();

        REQUIRED.forEach(name -> assertTrue(problem.contains(name), name));
    }

    @Test
    void treatsAnUnnamedCheckAsNotOneOfTheRequiredOnes()
    {
        checksReport(result(null, true));

        final String problem = this.filter.findProblem();

        assertTrue(problem.contains(LOGIN_CHECK));
        assertFalse(problem.contains("null"));
    }

    @Test
    void describesFailingChecksByStatusOnly()
    {
        everythingPassesExcept(SERVICES_CHECK);

        final String problem = this.filter.findProblem();

        assertTrue(problem.contains(SERVICES_CHECK + " is " + Result.Status.TEMPORARILY_UNAVAILABLE));
        assertFalse(problem.contains(RESULT_MESSAGE));
    }

    @Test
    void describesAnUnchangedSituationTheSameWayEveryTime()
    {
        checksReport(result(SERVICES_CHECK, false), result(LOGIN_CHECK, false), result(BUNDLES_CHECK, true));
        final String first = this.filter.findProblem();

        // The same results in another order
        checksReport(result(LOGIN_CHECK, false), result(BUNDLES_CHECK, true), result(SERVICES_CHECK, false));

        assertEquals(first, this.filter.findProblem());
    }

    @Test
    void selectsTheChecksByTag()
    {
        everythingPasses();
        final ArgumentCaptor<HealthCheckSelector> selector = ArgumentCaptor.forClass(HealthCheckSelector.class);

        this.filter.poll(0);

        verify(this.executor).execute(selector.capture(), any());
        assertEquals(List.of("systemalive"), List.of(selector.getValue().tags()));
    }

    @Test
    void requestsInstantExecutionSoEveryPollIsFreshEvidence()
    {
        everythingPasses();
        final ArgumentCaptor<HealthCheckExecutionOptions> options =
            ArgumentCaptor.forClass(HealthCheckExecutionOptions.class);

        this.filter.poll(0);

        verify(this.executor).execute(any(), options.capture());
        assertTrue(options.getValue().isForceInstantExecution());
        assertEquals(0, options.getValue().getOverrideGlobalTimeout());
    }

    @Test
    void staysClosedWhenTheChecksCannotBeExecuted() throws Exception
    {
        doThrow(new IllegalStateException("the executor is mid-restart")).when(this.executor).execute(any(), any());

        this.filter.poll(0);

        assertTrue(this.filter.findProblem().contains("the executor is mid-restart"));
        assertFalse(letsRequestsThrough());
        assertStillGated();
    }

    @Test
    void keepsPollingAfterTheChecksFailToExecute() throws Exception
    {
        doThrow(new IllegalStateException("the executor is mid-restart")).when(this.executor).execute(any(), any());
        this.filter.poll(0);

        everythingPasses();
        this.filter.poll(1);
        this.filter.poll(1 + StartupGateFilter.HOLD_NANOS);

        assertTrue(letsRequestsThrough());
    }

    @Test
    void closesAgainWhenReadinessRegresses() throws Exception
    {
        everythingPasses();
        this.filter.poll(0);

        everythingPassesExcept(LOGIN_CHECK);
        this.filter.poll(StartupGateFilter.SETTLE_NANOS);
        this.filter.doFilter(this.request, this.response, this.chain);

        verify(this.chain, never()).doFilter(this.request, this.response);
        verify(this.response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertStillGated();
    }

    @Test
    void doesNotRetireBeforeReadinessHasSettled()
    {
        everythingPasses();

        this.filter.poll(0);
        this.filter.poll(StartupGateFilter.SETTLE_NANOS - 1);

        assertStillGated();
    }

    @Test
    void retiresTheWholeGateOnceReadinessHasSettled()
    {
        everythingPasses();

        this.filter.poll(0);
        this.filter.poll(StartupGateFilter.SETTLE_NANOS);

        assertRetired();
    }

    @Test
    void neitherListensNorRetiresEarlyUnlessTheLaunchAsks()
    {
        everythingPasses();

        this.filter.poll(0);
        this.filter.poll(StartupGateFilter.QUIET_NANOS);

        assertStillGated();
        verify(this.bundleContext, never()).addServiceListener(any(AllServiceListener.class));
        verify(this.bundleContext, never()).addBundleListener(any());
    }

    @Test
    void retiresOnceReadinessHasHeldAndTheFrameworkIsQuietWhenTheLaunchAsks() throws IOException
    {
        retiringWhenQuiet();
        everythingPasses();

        this.filter.poll(0);
        this.filter.poll(StartupGateFilter.QUIET_NANOS - 1);

        assertStillGated();

        this.filter.poll(StartupGateFilter.QUIET_NANOS);

        assertRetired();
    }

    @Test
    void restartsTheSettleWindowAfterAnInterruption()
    {
        // Only the last ready stretch counts, not the time since the first ready poll
        everythingPasses();
        this.filter.poll(0);

        everythingPassesExcept(AUTH_CHECK);
        this.filter.poll(1);

        everythingPasses();
        this.filter.poll(2);
        this.filter.poll(StartupGateFilter.SETTLE_NANOS);

        assertStillGated();

        this.filter.poll(StartupGateFilter.SETTLE_NANOS + 2);

        assertRetired();
    }

    @Test
    void aServiceChangeRestartsTheQuietWindow() throws IOException
    {
        retiringWhenQuiet();
        everythingPasses();
        this.filter.poll(0);

        aServiceChanges();
        this.filter.poll(1);
        this.filter.poll(StartupGateFilter.QUIET_NANOS);

        assertStillGated();

        this.filter.poll(StartupGateFilter.QUIET_NANOS + 1);

        assertRetired();
    }

    @Test
    void aBundleChangeRestartsTheQuietWindow() throws IOException
    {
        retiringWhenQuiet();
        everythingPasses();
        this.filter.poll(0);

        aBundleChanges();
        this.filter.poll(1);
        this.filter.poll(StartupGateFilter.QUIET_NANOS);

        assertStillGated();

        this.filter.poll(StartupGateFilter.QUIET_NANOS + 1);

        assertRetired();
    }

    @Test
    void countsAChangeOnlyAgainstThePollThatFollowsIt() throws IOException
    {
        retiringWhenQuiet();
        everythingPasses();
        aServiceChanges();
        this.filter.poll(0);

        this.filter.poll(StartupGateFilter.QUIET_NANOS);

        assertRetired();
    }

    @Test
    void retiresAfterTheSettlePeriodEvenIfTheFrameworkNeverGoesQuiet() throws IOException
    {
        retiringWhenQuiet();
        everythingPasses();
        final long step = StartupGateFilter.QUIET_NANOS / 2;
        for (long now = 0; now < StartupGateFilter.SETTLE_NANOS; now += step) {
            aServiceChanges();
            this.filter.poll(now);
        }

        assertStillGated();

        aServiceChanges();
        this.filter.poll(StartupGateFilter.SETTLE_NANOS);

        assertRetired();
    }

    @Test
    void staysClosedUntilTheCeilingIsReached() throws Exception
    {
        everythingPassesExcept(BUNDLES_CHECK);

        this.filter.poll(0);
        this.filter.poll(StartupGateFilter.CEILING_NANOS - 1);

        assertFalse(letsRequestsThrough());
        assertStillGated();
    }

    @Test
    void opensAndStandsDownOnceItHasBeenClosedForTheWholeCeiling() throws Exception
    {
        everythingPassesExcept(BUNDLES_CHECK);

        this.filter.poll(0);
        this.filter.poll(StartupGateFilter.CEILING_NANOS);

        assertTrue(letsRequestsThrough());
        assertRetired();
    }

    @Test
    void reachesTheCeilingOnAFailingCheckThatIsNotEvenRequired() throws Exception
    {
        checksReport(Stream.concat(REQUIRED.stream(), Stream.of(CONTENT_CHECK)).toList(), CONTENT_CHECK);

        this.filter.poll(0);
        this.filter.poll(StartupGateFilter.CEILING_NANOS);

        assertTrue(letsRequestsThrough());
        verify(this.componentContext).disableComponent(StartupGateFilter.class.getName());
    }

    @Test
    void measuresTheCeilingFromTheFirstPollRatherThanFromZero() throws Exception
    {
        // System.nanoTime() has no fixed origin, so the first poll can read any value
        final long late = StartupGateFilter.CEILING_NANOS * 3;
        everythingPassesExcept(BUNDLES_CHECK);

        this.filter.poll(late);

        assertFalse(letsRequestsThrough());
        assertStillGated();

        this.filter.poll(late + StartupGateFilter.CEILING_NANOS);

        assertTrue(letsRequestsThrough());
    }

    @Test
    void doesNotReachTheCeilingWhileReadinessOnlyDipsBriefly() throws Exception
    {
        // A regression before the ceiling closes the gate again without opening it for good
        everythingPasses();
        this.filter.poll(0);

        everythingPassesExcept(LOGIN_CHECK);
        this.filter.poll(StartupGateFilter.CEILING_NANOS - 1);

        assertFalse(letsRequestsThrough());
        assertStillGated();
    }

    @Test
    void pollsWithTheCurrentTime() throws Exception
    {
        everythingPasses();

        this.filter.poll();

        // One poll cannot open the gate, since readiness has not held yet, so this asserts that the checks ran
        verify(this.executor).execute(any(), any());
        assertFalse(letsRequestsThrough());
        assertStillGated();
    }

    @Test
    void stopsPollingWhenDeactivated()
    {
        this.filter.deactivate();

        verify(this.poller).shutdownNow();
        verify(this.bundleContext, never()).removeServiceListener(any());
        verify(this.bundleContext, never()).removeBundleListener(any());
    }

    @Test
    void stopsPollingAndListeningWhenDeactivatedAfterAskingToRetireWhenQuiet() throws IOException
    {
        retiringWhenQuiet();
        final ArgumentCaptor<AllServiceListener> serviceListener = ArgumentCaptor.forClass(AllServiceListener.class);
        final ArgumentCaptor<BundleListener> bundleListener = ArgumentCaptor.forClass(BundleListener.class);
        verify(this.bundleContext).addServiceListener(serviceListener.capture());
        verify(this.bundleContext).addBundleListener(bundleListener.capture());

        this.filter.deactivate();

        verify(this.poller).shutdownNow();
        verify(this.bundleContext).removeServiceListener(serviceListener.getValue());
        verify(this.bundleContext).removeBundleListener(bundleListener.getValue());
    }

    @Test
    void pollsRepeatedlyOnItsOwnScheduleWhenActivatedByOsgi() throws IOException
    {
        everythingPasses();
        // Nothing below calls poll(), so only the component's own scheduler can run the checks
        final StartupGateFilter osgiFilter = new StartupGateFilter(this.executor, this.componentContext);

        try {
            verify(this.executor, timeout(10_000).atLeast(2)).execute(any(), any());
        } finally {
            osgiFilter.deactivate();
        }
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
