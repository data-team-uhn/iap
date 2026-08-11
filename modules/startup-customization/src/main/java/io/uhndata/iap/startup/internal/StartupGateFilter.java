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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.felix.hc.api.execution.HealthCheckExecutionOptions;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckSelector;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the static "starting up" page instead of half-working pages until the system is actually ready to face
 * visitors, everywhere except under {@code /system/}, which stays reachable throughout so that a stuck startup can be
 * diagnosed through the console and the health checks. Ready means that every one of the {@link #REQUIRED_CHECKS} is
 * registered and passing.
 *
 * <p>
 * Naming the checks, rather than trusting the {@code systemalive} tag to bring them all in, is what makes the gate
 * fail closed. The executor builds its results from the checks registered at that instant, so a check that is not
 * registered contributes no result at all rather than a failing one, and "every result passes" is trivially true of a
 * set that is empty or partial. That is not a rare race: five of the six checks are set up through OSGi
 * configuration, none of them declares a {@code modified} method, and so every configuration delivery -- including a
 * redelivery of the identical configuration, which is what the JCR installer does on the way up -- deactivates and
 * reactivates them. An earlier version of this class pinned only the login page check, which is the one check
 * declared in code with no configuration behind it, and therefore the one check that can never vanish; the five that
 * do vanish were the ones left unpinned, and the gate opened on a single passing check.
 * </p>
 *
 * <p>
 * Readiness is evaluated by a background poller rather than on the request thread, because running the checks is
 * expensive: it includes rendering the whole login page internally. Requests only read a volatile flag. Once the
 * system has been continuously ready for {@link #SETTLE_NANOS}, the gate retires for good: it disables the
 * placeholder context and servlet it needs during startup, then itself, leaving nothing behind on the request path.
 * The components are disabled, not permanently removed, so a restart arms the gate again.
 * </p>
 *
 * <p>
 * This intentionally duplicates a small part of the Felix Health Check {@code ServiceUnavailableFilter} instead of
 * using it, for two reasons learned the hard way. First, that filter is set up through OSGi configuration, so when the
 * JCR installer starts up and re-delivers configurations mid-startup, the filter component is restarted and requests
 * flow unfiltered for several seconds; this component is deliberately configuration-free so nothing restarts it.
 * Second, its equivalent of retiring fires on the first healthy result, and it counts an empty result set as healthy,
 * so the same churn makes it disappear long before the system is usable. The settle window here exists precisely
 * because readiness during startup is not monotonic: a page can render and then fail a second later when a late
 * bundle activation invalidates the scripting classloader, and any such regression reopens the gate and restarts the
 * window.
 * </p>
 *
 * <p>
 * Two alternatives were considered and rejected. Discovering the required checks at runtime, by remembering every
 * name ever seen, removes the hardcoded list but does not work: at the very beginning only a couple of checks exist,
 * they all pass, and the gate opens immediately. From in here, "not registered yet" and "registered a moment ago and
 * briefly gone again" are the same observation. Never retiring, and keeping the gate as a permanent safety net, would
 * cost a login page render twice a second forever; the filter itself is one volatile read per request and free, but
 * the poller behind it is not, so widening the settle window is the proportionate answer to a regression arriving
 * late.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Filter.class, property = {
    // Every request of every context; which ones to actually gate is decided in doFilter, because the whiteboard
    // matches this pattern against context-relative paths, so it cannot express a rule about absolute ones
    "osgi.http.whiteboard.filter.regex=.*",
    "osgi.http.whiteboard.context.select=(osgi.http.whiteboard.context.name=*)",
    "service.ranking:Integer=2147483647"
})
public final class StartupGateFilter implements Filter
{
    /**
     * How long the system must be continuously ready before the gate retires. Deliberately longer than the window in
     * which the JCR installer re-delivers configurations and bundles finish activating, because retiring is final: a
     * gate that stepped aside inside that window would be gone by the time the next activation invalidates the
     * scripting classloader. Retiring late costs nothing a visitor can see, since what they wait for is the gate
     * opening, not the gate retiring; retiring early costs them a raw 500.
     */
    static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** How often readiness is evaluated, in milliseconds, for as long as the gate is up. */
    static final long POLL_INTERVAL_MS = 500;

    private static final Logger LOGGER = LoggerFactory.getLogger(StartupGateFilter.class);

    /**
     * The health checks that must all be registered and passing before the gate opens. They are listed by name,
     * rather than counted or discovered, because a check that is not registered is invisible to the executor, so only
     * knowing what to expect can tell a partial set apart from a passing one. The names are duplicated here rather
     * than referenced because the checks live in bundles that start much later, and a compile-time dependency just
     * for a constant would force this early-starting bundle to wait for the whole Sling stack.
     */
    private static final Set<String> REQUIRED_CHECKS = Set.of(
        // io.uhndata.iap.healthcheck.internal.LoginPageReadyCheck, in the healthcheck module
        "Login Page Ready Check",
        // The five below are configured in packaging/slingfeature/src/main/features/healthcheck.json, which sets
        // hc.name explicitly for each of them so that an upstream default cannot change these names under us
        "OSGi Framework Ready Check",
        "Bundles Started",
        "Bundle Content Loaded",
        "Authentication Handler Ready Check",
        "Services Ready Check");

    /**
     * Runs the checks for real on every poll. The executor caches results for two seconds by default, which at this
     * poll interval would leave the settle window resting on a handful of fresh observations and one that may predate
     * it entirely. Doing it properly costs nothing in steady state, since the gate retires. The global timeout is
     * deliberately left alone: a check slower than it yields an error result, which keeps the gate shut for another
     * poll and recovers by itself, whereas a longer timeout would block this thread and delay the gate closing again.
     */
    private static final HealthCheckExecutionOptions INSTANT_EXECUTION =
        new HealthCheckExecutionOptions().setForceInstantExecution(true);

    /** The tag selecting the health checks that make up the gate. */
    private static final String GATE_TAG = "systemalive";

    /** Requests below this path are never gated: the console, the health check servlet and the readiness probes. */
    private static final String SYSTEM_PATH = "/system/";

    private final HealthCheckExecutor healthCheckExecutor;

    private final ComponentContext componentContext;

    private final ScheduledExecutorService poller;

    /** The static "starting up" page, also served by the launcher itself before the framework is up. */
    private final String startupPage;

    /** Whether requests may pass through. Written by the poller, read by every request. */
    private volatile boolean open;

    /** Whether the system is in an uninterrupted ready stretch. Only the poller touches this, and the field below. */
    private boolean settling;

    /** When the current ready stretch started; meaningless unless {@link #settling}, since any value is a valid one. */
    private long readySinceNanos;

    /**
     * What the log last said about readiness, so that a situation that is not changing is reported once rather than
     * twice a second. Starts as a value no evaluation can produce, so that the first one is always reported: a gate
     * that never opens must still say what it is waiting for.
     */
    private String reportedProblem = "not evaluated yet";

    /**
     * Constructor injection for OSGi.
     *
     * @param healthCheckExecutor executes the gating health checks, with its own short-lived result cache
     * @param componentContext used to disable this component, and the placeholder pair, once the system is up
     * @throws IOException if the startup page cannot be read from the classpath, which means a broken build
     */
    @Activate
    public StartupGateFilter(@Reference final HealthCheckExecutor healthCheckExecutor,
        final ComponentContext componentContext) throws IOException
    {
        this(healthCheckExecutor, componentContext, newPoller());
    }

    /**
     * Constructor taking the poller to use, so that tests can drive {@link #poll} themselves.
     *
     * @param healthCheckExecutor executes the gating health checks
     * @param componentContext used to disable the gate components once the system is up
     * @param poller schedules the readiness evaluations
     * @throws IOException if the startup page cannot be read from the classpath
     */
    StartupGateFilter(final HealthCheckExecutor healthCheckExecutor, final ComponentContext componentContext,
        final ScheduledExecutorService poller) throws IOException
    {
        this.healthCheckExecutor = healthCheckExecutor;
        this.componentContext = componentContext;
        this.poller = poller;
        try (InputStream stub = Objects.requireNonNull(StartupGateFilter.class.getResourceAsStream(
            "/custom_index.html"))) {
            this.startupPage = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(stub.readAllBytes())).toString();
        }
        // The initial delay also keeps the poller from running against a half-constructed object
        this.poller.scheduleWithFixedDelay(this::poll, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * A single-threaded scheduler running one daemon thread, so it can never hold up a shutdown.
     *
     * @return a scheduler for the readiness poller
     */
    static ScheduledExecutorService newPoller()
    {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "IAP startup gate");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Stops the poller when the component goes away, including when the gate retires itself. */
    @Deactivate
    void deactivate()
    {
        this.poller.shutdownNow();
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
        throws IOException, ServletException
    {
        if (this.open || ((HttpServletRequest) request).getRequestURI().startsWith(SYSTEM_PATH)) {
            chain.doFilter(request, response);
            return;
        }
        final HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        httpResponse.setContentType("text/html;charset=UTF-8");
        // The page changes from one moment to the next, and the stub itself must never be cached
        httpResponse.setHeader("Cache-Control", "no-store");
        httpResponse.getWriter().write(this.startupPage);
    }

    /** Evaluates readiness, as scheduled. */
    void poll()
    {
        poll(System.nanoTime());
    }

    /**
     * Evaluates readiness once: opens or closes the gate, and retires it once it has been open long enough.
     *
     * @param nowNanos the current value of the nanosecond timer
     */
    void poll(final long nowNanos)
    {
        final String problem = findProblem();
        report(problem);
        final boolean ready = problem == null;
        // Not a one-way latch: a system that stops being ready mid-startup gets the stub back
        this.open = ready;
        if (!ready) {
            this.settling = false;
        } else if (!this.settling) {
            this.settling = true;
            this.readySinceNanos = nowNanos;
        } else if (nowNanos - this.readySinceNanos >= SETTLE_NANOS) {
            retire();
        }
    }

    /**
     * Takes the whole gate out of the way, now that the system has proven itself started: no more polling, and none of
     * the three components that make up the gate are left registered.
     */
    private void retire()
    {
        LOGGER.info("The system is fully started, retiring the startup gate");
        this.poller.shutdown();
        this.componentContext.disableComponent(StartupPlaceholderServlet.class.getName());
        this.componentContext.disableComponent(StartupPlaceholderContext.class.getName());
        // Last, since this ends up deactivating the very component running this code
        this.componentContext.disableComponent(StartupGateFilter.class.getName());
    }

    /**
     * Evaluates the gating health checks. Package private so that the tests can assert on the reason directly, which
     * is also what keeps the reason and the gate from ever disagreeing: this is the only thing that decides either.
     *
     * @return {@code null} if every required check is registered and passing, otherwise a description of what is not,
     *         worded so that a situation that has not changed produces the same description again
     */
    String findProblem()
    {
        final Set<String> missing = new TreeSet<>(REQUIRED_CHECKS);
        final Set<String> failing = new TreeSet<>();
        try {
            final List<HealthCheckExecutionResult> results =
                this.healthCheckExecutor.execute(HealthCheckSelector.tags(GATE_TAG), INSTANT_EXECUTION);
            for (final HealthCheckExecutionResult result : results) {
                // getName reads the hc.name service property raw, so a check registered without one reads as null
                final String name = Objects.toString(result.getHealthCheckMetadata().getName(), "(unnamed)");
                missing.remove(name);
                if (!result.getHealthCheckResult().isOk()) {
                    // The status only, never the result message: those carry timings and stack traces, which would
                    // make a situation that is not changing look different on every poll
                    failing.add(name + " is " + result.getHealthCheckResult().getStatus());
                }
            }
        } catch (final Exception e) {
            // An exception escaping into the scheduler cancels every further poll, which would leave the gate shut
            // for the life of the process with nothing in the log to say why
            return "the health checks cannot be executed: " + e;
        }
        if (missing.isEmpty() && failing.isEmpty()) {
            return null;
        }
        return "waiting for " + missing + ", failing " + failing;
    }

    /**
     * Logs what the gate is doing, on change only: a handful of lines over a startup, and none at all once it is over.
     *
     * @param problem what is keeping the gate shut, or {@code null} if the system is ready
     */
    private void report(final String problem)
    {
        if (Objects.equals(problem, this.reportedProblem)) {
            return;
        }
        this.reportedProblem = problem;
        if (problem == null) {
            LOGGER.info("Every startup check passes, letting requests through");
        } else {
            LOGGER.info("Serving the startup page, {}", problem);
        }
    }
}
