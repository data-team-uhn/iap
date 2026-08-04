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

import org.apache.felix.hc.api.Result;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckMetadata;
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
 * diagnosed through the console and the health checks. Ready means that all the health checks tagged
 * {@code systemalive} pass, and that among them is the one verifying that the login page renders. Requiring that
 * specific check is what makes the gate fail closed: during startup the check components themselves flicker in and out
 * of existence while their configurations are delivered, and a transiently empty or partial set of checks must keep
 * the gate shut, not open it.
 *
 * <p>
 * Readiness is evaluated by a background poller rather than on the request thread, because running the checks is
 * expensive: the executor caches results for two seconds only, so one request every two seconds would pay for a full
 * re-execution, including rendering the whole login page internally. Requests only read a volatile flag. Once the
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
    /** How long the system must be continuously ready before the gate retires. */
    static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(5);

    /** How often readiness is evaluated, in milliseconds, for as long as the gate is up. */
    static final long POLL_INTERVAL_MS = 500;

    private static final Logger LOGGER = LoggerFactory.getLogger(StartupGateFilter.class);

    /**
     * The name of the health check that must be present and passing before the gate opens: the LoginPageReadyCheck in
     * the healthcheck module, which renders the login page internally. The name is duplicated here rather than
     * referenced because the two bundles are otherwise independent, and a compile-time dependency just for a constant
     * would force this early-starting bundle to wait for the whole Sling stack.
     */
    private static final String REQUIRED_CHECK = "Login Page Ready Check";

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
        final boolean ready = isReady();
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

    private boolean isReady()
    {
        final List<HealthCheckExecutionResult> results =
            this.healthCheckExecutor.execute(HealthCheckSelector.tags(GATE_TAG));
        return results.stream()
            .map(HealthCheckExecutionResult::getHealthCheckMetadata)
            .map(HealthCheckMetadata::getName)
            .anyMatch(REQUIRED_CHECK::equals)
            && results.stream()
                .map(HealthCheckExecutionResult::getHealthCheckResult)
                .allMatch(Result::isOk);
    }
}
