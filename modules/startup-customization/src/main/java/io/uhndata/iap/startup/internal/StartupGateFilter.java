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
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.osgi.framework.AllServiceListener;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleListener;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the "starting up" page until IAP is ready for visitors. Requests under {@code /system/} always pass, so the
 * console and the health checks can diagnose a startup that does not finish.
 *
 * <p>
 * IAP is ready when every one of the {@link #REQUIRED_CHECKS} is registered and every registered health check
 * tagged {@code systemalive} passes. The gate opens once that has held for {@link #HOLD_NANOS}, and closes again
 * whenever it stops holding. A background poller decides, and a request only reads its decision.
 * </p>
 *
 * <p>
 * Once IAP has been ready for {@link #SETTLE_NANOS}, the gate retires. It disables the placeholder servlet, the
 * placeholder context and itself, so nothing of it stays on the request path. If IAP is still not ready
 * {@link #CEILING_NANOS} after the first poll, the gate opens anyway and retires.
 * </p>
 *
 * <p>
 * The gate takes no configuration, so the configuration deliveries during startup never restart it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Filter.class, property = {
    // Every request in every context. The whiteboard matches this pattern against the path inside the context, and
    // /system/console/bundles.json reaches it as /bundles.json, so doFilter exempts /system/ instead.
    "osgi.http.whiteboard.filter.regex=.*",
    "osgi.http.whiteboard.context.select=(osgi.http.whiteboard.context.name=*)",
    "service.ranking:Integer=2147483647"
})
public final class StartupGateFilter implements Filter
{
    /**
     * The framework property that, set to {@code true}, also lets the gate retire after {@link #QUIET_NANOS} of
     * readiness and quiet, without waiting out {@link #SETTLE_NANOS}.
     */
    static final String RETIRE_WHEN_QUIET = "iap.startup.gate.retireWhenQuiet";

    /**
     * How long IAP must be ready and the framework quiet before a launch that sets {@link #RETIRE_WHEN_QUIET} retires
     * the gate. Quiet means no bundle event and no service event. Configuration deliveries and late bundle activations
     * both show up as such events, and either can still break the pages.
     */
    static final long QUIET_NANOS = TimeUnit.SECONDS.toNanos(10);

    /**
     * How long IAP must be ready, without a break, before the gate retires. It outlasts the configuration deliveries
     * and the late bundle activations of a startup. A retired gate stays retired until a restart, so a page that
     * breaks after that reaches visitors as a raw error.
     */
    static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(30);

    /**
     * How long readiness must hold before the gate opens. During startup each configuration delivery restarts the
     * check it configures, and a check that is restarting gives no result at all. One poll that finds every check
     * registered and passing can fall in the middle of that.
     */
    static final long HOLD_NANOS = TimeUnit.SECONDS.toNanos(5);

    /**
     * How long after its first poll the gate may still hold requests. Past it, the next poll that finds IAP not ready
     * opens the gate and retires it.
     *
     * <p>
     * Some failures can only be cleared by an administrator who logs in. Content that failed to load because an
     * upgrade left merge conflicts is one. A gate that never opened would lock that administrator out for good, since
     * the tools that resolve the conflicts are not under {@link #SYSTEM_PATH}.
     * </p>
     */
    static final long CEILING_NANOS = TimeUnit.MINUTES.toNanos(5);

    /** How often readiness is evaluated, in milliseconds, for as long as the gate is up. */
    static final long POLL_INTERVAL_MS = 500;

    private static final Logger LOGGER = LoggerFactory.getLogger(StartupGateFilter.class);

    /**
     * The health checks that must be registered before the gate opens. The executor returns results only for the
     * checks registered at that moment, so a missing check gives no result rather than a failing one. Only a list of
     * the expected names tells an incomplete set from a passing one.
     *
     * <p>
     * The {@code systemalive} tag decides which checks must pass, and this list decides which of them must be there.
     * A tagged check that is not listed holds the gate shut only while it is registered and failing.
     * </p>
     *
     * <p>
     * {@code Bundle Content Loaded} is tagged but not listed. A check whose remedy needs somebody logged in must
     * never gate logging in, and content that failed to load after an upgrade needs an administrator to fix it.
     * While that check fails it still holds the gate, until {@link #CEILING_NANOS}. Its component also does not
     * register on every start. <strong>Do not list it if its registration becomes reliable</strong>: the first reason
     * still holds.
     * </p>
     */
    private static final Set<String> REQUIRED_CHECKS = Set.of(
        // io.uhndata.iap.healthcheck.internal.LoginPageReadyCheck, in the healthcheck module
        "Login Page Ready Check",
        // The four below are configured in packaging/slingfeature/src/main/features/healthcheck.json, which sets each
        // one's hc.name, so a new default name in a Felix or Sling release cannot change them
        "OSGi Framework Ready Check",
        "Bundles Started",
        "Authentication Handler Ready Check",
        "Services Ready Check");

    /**
     * Runs every check afresh on each poll, instead of reusing the executor's cached results, which last two seconds
     * by default. The executor's timeout stays as it is: a check slower than that comes back as a failure, and the
     * next poll asks again.
     */
    private static final HealthCheckExecutionOptions INSTANT_EXECUTION =
        new HealthCheckExecutionOptions().setForceInstantExecution(true);

    /** The tag of the health checks that must pass before the gate opens. */
    private static final String GATE_TAG = "systemalive";

    /** Requests below this path are never held: the web console, the health checks and Sling's system pages. */
    private static final String SYSTEM_PATH = "/system/";

    private final HealthCheckExecutor healthCheckExecutor;

    private final ComponentContext componentContext;

    private final ScheduledExecutorService poller;

    /** Whether {@link #RETIRE_WHEN_QUIET} is set. Without it nothing listens, and only {@link #SETTLE_NANOS} counts. */
    private final boolean retireWhenQuiet;

    /** Whether a bundle or service changed since the last poll. Set by the listeners below, cleared by the poller. */
    private final AtomicBoolean frameworkChanged = new AtomicBoolean();

    private final AllServiceListener serviceListener = event -> this.frameworkChanged.set(true);

    private final BundleListener bundleListener = event -> this.frameworkChanged.set(true);

    /** The "starting up" page, as HTML. */
    private final String startupPage;

    /** Whether requests may pass through. Written by the poller, read by every request. */
    private volatile boolean open;

    /** Whether IAP has been ready since {@link #readySinceNanos}. Only the poller reads and writes it. */
    private boolean settling;

    /** When the current stretch of readiness started. Meaningless unless {@link #settling}. */
    private long readySinceNanos;

    /** Whether {@link #firstPollNanos} has been taken. The timer can read zero, so zero cannot mean "not yet". */
    private boolean polled;

    /** When the first poll ran. {@link #CEILING_NANOS} counts from it. */
    private long firstPollNanos;

    /** The first poll after the latest bundle or service change, or the first poll if nothing has changed since. */
    private long quietSinceNanos;

    /**
     * What the log last said about readiness, so the same situation is logged once rather than on every poll. The
     * initial value is one no poll produces, so the first poll is always logged.
     */
    private String reportedState = "not evaluated yet";

    /**
     * Creates the gate, closed, and starts polling.
     *
     * @param healthCheckExecutor runs the health checks tagged {@code systemalive}
     * @param componentContext gives the framework properties, and disables this component and the placeholder servlet
     *            and context when the gate retires
     * @throws IOException if the startup page cannot be read from the bundle, which means a broken build
     */
    @Activate
    public StartupGateFilter(@Reference final HealthCheckExecutor healthCheckExecutor,
        final ComponentContext componentContext) throws IOException
    {
        this(healthCheckExecutor, componentContext, newPoller());
    }

    /**
     * Creates the gate with the scheduler to poll on, so the tests can call {@link #poll} themselves.
     *
     * @param healthCheckExecutor runs the health checks tagged {@code systemalive}
     * @param componentContext gives the framework properties, and disables this component and the placeholder servlet
     *            and context when the gate retires
     * @param poller the scheduler that runs {@link #poll} every {@link #POLL_INTERVAL_MS}
     * @throws IOException if the startup page cannot be read from the bundle
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
        final BundleContext bundleContext = componentContext.getBundleContext();
        this.retireWhenQuiet = Boolean.parseBoolean(bundleContext.getProperty(RETIRE_WHEN_QUIET));
        if (this.retireWhenQuiet) {
            bundleContext.addServiceListener(this.serviceListener);
            bundleContext.addBundleListener(this.bundleListener);
        }
        this.poller.scheduleWithFixedDelay(this::poll, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates the scheduler that polls readiness. It runs one daemon thread, which never keeps the JVM from exiting.
     *
     * @return a new single-threaded scheduler
     */
    static ScheduledExecutorService newPoller()
    {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "IAP startup gate");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Stops the poller and the listeners, both when the component is stopped and when the gate retires. */
    @Deactivate
    void deactivate()
    {
        this.poller.shutdownNow();
        if (this.retireWhenQuiet) {
            final BundleContext bundleContext = this.componentContext.getBundleContext();
            bundleContext.removeServiceListener(this.serviceListener);
            bundleContext.removeBundleListener(this.bundleListener);
        }
    }

    @Override
    // The startup page is HTML
    @SuppressWarnings("checkstyle:directResponseWriter")
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
        // The real page replaces the stub at this URL once the gate opens, so the browser must not keep the stub
        httpResponse.setHeader("Cache-Control", "no-store");
        httpResponse.getWriter().write(this.startupPage);
    }

    /** Evaluates readiness now, on the scheduler's thread. */
    void poll()
    {
        poll(System.nanoTime());
    }

    /**
     * Evaluates readiness once, and opens, closes or retires the gate to match.
     *
     * @param nowNanos the current time, as read from {@link System#nanoTime()}
     */
    void poll(final long nowNanos)
    {
        if (!this.polled) {
            this.polled = true;
            this.firstPollNanos = nowNanos;
            this.quietSinceNanos = nowNanos;
        }
        if (this.frameworkChanged.getAndSet(false)) {
            this.quietSinceNanos = nowNanos;
        }
        final String problem = findProblem();
        if (problem != null) {
            // An open gate closes again when IAP stops being ready, until it retires
            this.settling = false;
            this.open = false;
            report(problem, false);
            if (nowNanos - this.firstPollNanos >= CEILING_NANOS) {
                giveUp(problem);
            }
            return;
        }
        if (!this.settling) {
            this.settling = true;
            this.readySinceNanos = nowNanos;
        }
        final long readyForNanos = nowNanos - this.readySinceNanos;
        this.open = readyForNanos >= HOLD_NANOS;
        report(null, this.open);
        if (readyForNanos >= SETTLE_NANOS
            || (this.retireWhenQuiet && readyForNanos >= QUIET_NANOS
                && nowNanos - this.quietSinceNanos >= QUIET_NANOS)) {
            retire();
        }
    }

    /** Removes the gate from the request path, once IAP has been ready long enough. */
    private void retire()
    {
        LOGGER.info("The system is fully started, retiring the startup gate");
        standDown();
    }

    /**
     * Opens the gate and retires it, although IAP is not ready. Logs an error that says what is still wrong, since the
     * pages behind the gate may not work.
     *
     * @param problem what kept IAP from being ready at this poll, as {@link #findProblem} describes it
     */
    private void giveUp(final String problem)
    {
        LOGGER.error("The startup gate has been holding requests for {} seconds and the system is still not ready:"
            + " {}. Opening it anyway, because a gate that never opens leaves the instance permanently unreachable,"
            + " and clearing this may need an administrator to log in. Expect pages that do not work until it is"
            + " resolved.", TimeUnit.NANOSECONDS.toSeconds(CEILING_NANOS), problem);
        this.open = true;
        standDown();
    }

    /**
     * Stops polling and disables the placeholder servlet, the placeholder context and this filter. A restart of the
     * bundle or the framework enables them again.
     */
    private void standDown()
    {
        this.poller.shutdown();
        this.componentContext.disableComponent(StartupPlaceholderServlet.class.getName());
        this.componentContext.disableComponent(StartupPlaceholderContext.class.getName());
        // Last, since this deactivates the component running this code
        this.componentContext.disableComponent(StartupGateFilter.class.getName());
    }

    /**
     * Runs the health checks tagged {@code systemalive} and says what keeps IAP from being ready.
     *
     * @return {@code null} if every one of the {@link #REQUIRED_CHECKS} is registered and every registered tagged
     *         check passes. Otherwise the missing and the failing checks, such as
     *         {@code waiting for [Login Page Ready Check], failing [Bundles Started is CRITICAL]}. If the checks
     *         could not run, the exception that stopped them. The same missing and failing checks always give the
     *         same text.
     */
    String findProblem()
    {
        final Set<String> missing = new TreeSet<>(REQUIRED_CHECKS);
        final Set<String> failing = new TreeSet<>();
        try {
            final List<HealthCheckExecutionResult> results =
                this.healthCheckExecutor.execute(HealthCheckSelector.tags(GATE_TAG), INSTANT_EXECUTION);
            for (final HealthCheckExecutionResult result : results) {
                // A check registered without an hc.name property has no name
                final String name = Objects.toString(result.getHealthCheckMetadata().getName(), "(unnamed)");
                missing.remove(name);
                if (!result.getHealthCheckResult().isOk()) {
                    // The status, not the message: messages carry timings and stack traces, which differ on every
                    // poll and would log the same situation again each time
                    failing.add(name + " is " + result.getHealthCheckResult().getStatus());
                }
            }
        } catch (final Exception e) {
            // An exception that reaches the scheduler cancels every later poll, and the gate would stay shut with
            // nothing in the log to say why
            return "the health checks cannot be executed: " + e;
        }
        if (missing.isEmpty() && failing.isEmpty()) {
            return null;
        }
        return "waiting for " + missing + ", failing " + failing;
    }

    /**
     * Logs what the gate is doing, when that changes. Passing the checks and letting requests through are logged
     * apart, since requests wait {@link #HOLD_NANOS} after the checks first pass.
     *
     * @param problem what keeps IAP from being ready, as {@link #findProblem} describes it, or {@code null} if it is
     *            ready
     * @param passing whether the gate lets requests through
     */
    private void report(final String problem, final boolean passing)
    {
        final String state;
        if (problem != null) {
            state = "Serving the startup page, " + problem;
        } else if (passing) {
            state = "Every startup check passes and has held, letting requests through";
        } else {
            state = "Every startup check passes, holding requests for up to "
                + TimeUnit.NANOSECONDS.toSeconds(HOLD_NANOS) + "s in case readiness does not last";
        }
        if (Objects.equals(state, this.reportedState)) {
            return;
        }
        this.reportedState = state;
        LOGGER.info(state);
    }
}
