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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.felix.hc.api.Result;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckMetadata;
import org.apache.felix.hc.api.execution.HealthCheckSelector;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Serves the static "starting up" page instead of half-working pages until the system is actually ready to face
 * visitors: all the health checks tagged {@code systemalive} must pass, and among them there must be the one that
 * verifies that the login page renders. Requiring that specific check is what makes the gate fail closed: during
 * startup the check components themselves flicker in and out of existence while their configurations are delivered,
 * and a transiently empty or partial set of checks must keep the gate shut, not open it.
 *
 * <p>
 * This intentionally duplicates a small part of the Felix Health Check {@code ServiceUnavailableFilter} instead of
 * using it, for two reasons learned the hard way. First, that filter is set up through OSGi configuration, so when the
 * JCR installer starts up and re-delivers configurations mid-startup, the filter component is restarted and requests
 * flow unfiltered for several seconds; this component is deliberately configuration-free so nothing restarts it.
 * Second, that filter treats an empty result set as healthy, which during the same churn opens the gate long before
 * the system is usable.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Filter.class, property = {
    // Everything except /system/, where the console, the health check servlet, and the readiness probes live
    "osgi.http.whiteboard.filter.regex=(?!/system/).*",
    "osgi.http.whiteboard.context.select=(osgi.http.whiteboard.context.name=*)",
    "service.ranking:Integer=2147483647"
})
public final class StartupGateFilter implements Filter
{
    /**
     * The name of the health check that must be present and passing before the gate opens: the LoginPageReadyCheck in
     * the healthcheck module, which renders the login page internally. The name is duplicated here rather than
     * referenced because the two bundles are otherwise independent, and a compile-time dependency just for a constant
     * would force this early-starting bundle to wait for the whole Sling stack.
     */
    private static final String REQUIRED_CHECK = "Login Page Ready Check";

    /** The tag selecting the health checks that make up the gate. */
    private static final String GATE_TAG = "systemalive";

    private final HealthCheckExecutor healthCheckExecutor;

    /** The static "starting up" page, also served by the launcher itself before the framework is up. */
    private final String startupPage;

    /**
     * Constructor injection, both for OSGi and for the tests.
     *
     * @param healthCheckExecutor executes the gating health checks, with its own short-lived result cache
     * @throws IOException if the startup page cannot be read from the classpath, which means a broken build
     */
    @Activate
    public StartupGateFilter(@Reference final HealthCheckExecutor healthCheckExecutor) throws IOException
    {
        this.healthCheckExecutor = healthCheckExecutor;
        try (InputStream stub = Objects.requireNonNull(StartupGateFilter.class.getResourceAsStream(
            "/custom_index.html"))) {
            this.startupPage = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(stub.readAllBytes())).toString();
        }
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
        throws IOException, ServletException
    {
        if (isReady()) {
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
