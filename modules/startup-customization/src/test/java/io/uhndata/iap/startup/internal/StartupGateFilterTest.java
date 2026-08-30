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
     * The checks the gate requires, restated here rather than read from the filter: an independent list is what makes
     * an accidental edit to the constant fail a test, instead of quietly weakening the gate along with its tests.
     */
    private static final List<String> REQUIRED =
        List.of(LOGIN_CHECK, FRAMEWORK_CHECK, BUNDLES_CHECK, AUTH_CHECK, SERVICES_CHECK);

    /** Stands in for the timings and stack traces real checks report, which must not reach the log on every poll. */
    private static final String RESULT_MESSAGE = "diagnostics that must not be part of the description";

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
            .thenReturn(new Result(ok ? Result.Status.OK : Result.Status.TEMPORARILY_UNAVAILABLE, RESULT_MESSAGE));
        return result;
    }

    /**
     * Stubs what the executor reports. Uses {@code doReturn} rather than {@code when}, so that it can also re-stub an
     * executor that was previously made to throw; and the results are always built before the executor is stubbed,
     * because nested stubbing confuses Mockito.
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

    /** The whole gating set, registered and passing: what a started system looks like. */
    private void everythingPasses()
    {
        checksReport(REQUIRED, null);
    }

    /** The whole gating set, registered, with a single check failing, so that only that one thing is under test. */
    private void everythingPassesExcept(final String failing)
    {
        checksReport(REQUIRED, failing);
    }

    /**
     * Asks the gate, without touching the shared response and chain mocks, so that this can be called in a loop.
     *
     * @return whether a request would reach the application rather than the startup page
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
    void opensOnlyWhenEveryRequiredCheckIsPresentAndPassingAndHasStayedThatWay() throws Exception
    {
        everythingPasses();

        // One passing evaluation says only that the checks lined up at that instant, which they do transiently
        // while configurations are being re-delivered
        this.filter.poll(0);
        assertFalse(letsRequestsThrough());

        this.filter.poll(StartupGateFilter.HOLD_NANOS);
        this.filter.doFilter(this.request, this.response, this.chain);

        assertNull(this.filter.findProblem());
        verify(this.chain).doFilter(this.request, this.response);
        verify(this.response, never()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    /**
     * The regression test for the gate letting a half-started instance through, reproducing what a real startup did:
     * the checks passed, went unavailable two seconds later, and came back two seconds after that. The hold has to
     * restart with the new stretch, or four seconds of flapping is mistaken for four seconds of readiness.
     */
    @Test
    void aRegressionInsideTheHoldWindowStartsTheHoldAgain() throws Exception
    {
        final long twoSeconds = TimeUnit.SECONDS.toNanos(2);
        everythingPasses();
        this.filter.poll(0);

        everythingPassesExcept(SERVICES_CHECK);
        this.filter.poll(twoSeconds);
        everythingPasses();
        this.filter.poll(2 * twoSeconds);

        // Long enough since the FIRST ready evaluation, but the stretch that counts began at four seconds
        assertFalse(letsRequestsThrough(), "readiness that did not last is not readiness");

        this.filter.poll(2 * twoSeconds + StartupGateFilter.HOLD_NANOS);
        assertTrue(letsRequestsThrough());
    }

    @Test
    void staysClosedWhileAnyRequiredCheckIsMissing() throws Exception
    {
        // The regression test for the gate opening early: a check that is not registered contributes no result at
        // all rather than a failing one, so an incomplete set passes "every result is OK" without any of them saying
        // anything is wrong. Every one of the required checks is briefly unregistered during startup.
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
        // The tag decides which checks the gate consults, the required list decides which of them have to be there.
        // Bundle Content Loaded is tagged but never registers, and requiring it would hang the gate forever.
        everythingPasses();

        assertNull(this.filter.findProblem());
    }

    @Test
    void staysClosedWhenATaggedCheckThatIsNotRequiredFails() throws Exception
    {
        // The other half of that split: a check does not have to be on the required list to hold the gate shut once
        // it is actually there, so a check added to the tag starts gating on its own.
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
        // Every present check passes, but the one that proves the login page renders is not registered
        // (yet, or anymore): the gate must fail closed rather than trust an incomplete picture.
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
        // The whole configured set disappears at once every time the JCR installer re-delivers its configurations
        checksReport();

        final String problem = this.filter.findProblem();

        REQUIRED.forEach(name -> assertTrue(problem.contains(name), name));
    }

    @Test
    void treatsAnUnnamedCheckAsNotOneOfTheRequiredOnes()
    {
        // getName is a raw read of the hc.name property, so a check registered without one reads as null here
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
        // Real check messages carry timings and stack traces, which would describe an unchanged situation
        // differently on every poll, and so log a line twice a second for the whole of startup
        assertFalse(problem.contains(RESULT_MESSAGE));
    }

    @Test
    void describesAnUnchangedSituationTheSameWayEveryTime()
    {
        // What lets the reporting log on change only: the executor returns its results in registration order, so the
        // description has to impose one of its own or it would look different on every poll
        checksReport(result(SERVICES_CHECK, false), result(LOGIN_CHECK, false), result(BUNDLES_CHECK, true));
        final String first = this.filter.findProblem();

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
        // Overriding the timeout would block the poller instead of letting a slow check fail and recover, so if this
        // ever becomes deliberate it should be a deliberate edit here too
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
        // An exception escaping into the scheduler cancels every further poll, and the gate would never open again
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
        everythingPasses();
        this.filter.poll(0);

        everythingPassesExcept(AUTH_CHECK);
        this.filter.poll(1);

        everythingPasses();
        this.filter.poll(2);
        this.filter.poll(StartupGateFilter.SETTLE_NANOS);

        assertStillGated();

        this.filter.poll(StartupGateFilter.SETTLE_NANOS + 2);

        verify(this.componentContext).disableComponent(StartupGateFilter.class.getName());
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
        // The instance must not be left unreachable by a check that only an administrator can clear, so the gate
        // gives up rather than waiting for something that will never happen on its own.
        everythingPassesExcept(BUNDLES_CHECK);

        this.filter.poll(0);
        this.filter.poll(StartupGateFilter.CEILING_NANOS);

        assertTrue(letsRequestsThrough());
        verify(this.componentContext).disableComponent(StartupPlaceholderServlet.class.getName());
        verify(this.componentContext).disableComponent(StartupPlaceholderContext.class.getName());
        verify(this.componentContext).disableComponent(StartupGateFilter.class.getName());
        verify(this.poller).shutdown();
    }

    @Test
    void reachesTheCeilingOnAFailingCheckThatIsNotEvenRequired() throws Exception
    {
        // The case the ceiling exists for: content that failed to load reports a failure rather than vanishing, and
        // leaving the check off the required list does nothing about that.
        checksReport(Stream.concat(REQUIRED.stream(), Stream.of(CONTENT_CHECK)).toList(), CONTENT_CHECK);

        this.filter.poll(0);
        this.filter.poll(StartupGateFilter.CEILING_NANOS);

        assertTrue(letsRequestsThrough());
        verify(this.componentContext).disableComponent(StartupGateFilter.class.getName());
    }

    @Test
    void measuresTheCeilingFromTheFirstPollRatherThanFromZero() throws Exception
    {
        // The timer has no epoch, so a gate whose first poll lands on a large value must not read as long overdue.
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
        // A regression closes the gate again, but that is not a reason to give up: the ceiling bounds the whole
        // startup, so a dip late in a slow one must not be mistaken for a system that never came up.
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

        // What the no-argument form owes is that it evaluates against the real clock. It cannot be asserted by the
        // gate opening any more — nothing has held yet — so assert the evaluation itself.
        verify(this.executor).execute(any(), any());
        assertFalse(letsRequestsThrough());
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
        everythingPasses();
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
