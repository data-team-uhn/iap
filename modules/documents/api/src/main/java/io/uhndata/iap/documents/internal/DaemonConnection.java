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
package io.uhndata.iap.documents.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Where the document daemon listens and how to call it. Every component that calls the daemon reads these settings
 * from the one shared configuration, {@link #DAEMON_PID}, so they are set in one place.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DaemonConnection
{
    /** The PID of the shared configuration that every component calling the daemon reads. */
    static final String DAEMON_PID = "io.uhndata.iap.documents.internal.DaemonConnection";

    /** Where the daemon listens when no {@code daemonUrl} is configured. */
    static final String DEFAULT_URL = "http://localhost:18765";

    /** How many seconds to wait for the daemon to answer when no {@code responseTimeout} is configured. */
    static final long DEFAULT_RESPONSE_TIMEOUT = 30;

    /** How long to wait for the daemon to accept a connection. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger LOGGER = LoggerFactory.getLogger(DaemonConnection.class);

    private final String url;

    private final Duration responseTimeout;

    private final String authorization;

    private DaemonConnection(final String url, final Duration responseTimeout, final String authorization)
    {
        this.url = url;
        this.responseTimeout = responseTimeout;
        this.authorization = authorization;
    }

    /**
     * Reads the daemon settings from a component's configuration.
     *
     * @param configuration the component configuration, see {@link DaemonConnectionConfiguration}
     * @param environment returns an environment variable by name, or {@code null} when it is not set
     * @return the settings; a missing or unusable URL or timeout gets its default, and with no token set no
     *         {@code Authorization} header is sent
     */
    static DaemonConnection read(final Map<String, Object> configuration, final Function<String, String> environment)
    {
        final String configured = String.valueOf(configuration.getOrDefault("daemonUrl", DEFAULT_URL));
        final String url = (configured.isBlank() ? DEFAULT_URL : configured).replaceAll("/+$", "");
        long seconds = DEFAULT_RESPONSE_TIMEOUT;
        final Object timeout = configuration.get("responseTimeout");
        if (timeout != null) {
            try {
                seconds = Long.parseLong(String.valueOf(timeout));
            } catch (final NumberFormatException e) {
                LOGGER.warn("Ignoring non-numeric responseTimeout: {}", timeout);
                ErrorLogger.logProblem("Ignoring a non-numeric responseTimeout for the document daemon",
                    ErrorContext.of(DaemonConnection.class, "read").with("responseTimeout", timeout));
            }
        }
        final String token = getToken(configuration, environment);
        return new DaemonConnection(url, Duration.ofSeconds(seconds > 0 ? seconds : DEFAULT_RESPONSE_TIMEOUT),
            token.isEmpty() ? null : "Bearer " + token);
    }

    /** Returns the daemon's own token: the configured one, else the environment variable, else empty. */
    private static String getToken(final Map<String, Object> configuration,
        final Function<String, String> environment)
    {
        final String configured =
            String.valueOf(configuration.getOrDefault(ParseJob.DAEMON_TOKEN_PROPERTY, "")).trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        final String variable = environment.apply(ParseJob.DAEMON_TOKEN_VARIABLE);
        return variable == null ? "" : variable.trim();
    }

    /**
     * Creates a client for calling the daemon.
     *
     * @return a new client that gives up when the daemon does not accept the connection within 10 seconds
     */
    static HttpClient newClient()
    {
        return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * Builds a POST to the daemon with no body, the response timeout, and the token when one is set.
     *
     * @param pathAndQuery the part after the base URL, already encoded, for example {@code /cancel?job_id=...}
     * @return the request
     */
    HttpRequest post(final String pathAndQuery)
    {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.url + pathAndQuery))
            .timeout(this.responseTimeout)
            .POST(HttpRequest.BodyPublishers.noBody());
        if (this.authorization != null) {
            request.header("Authorization", this.authorization);
        }
        return request.build();
    }

    /**
     * Returns where the daemon listens.
     *
     * @return the base URL, without a trailing slash
     */
    String getUrl()
    {
        return this.url;
    }

    /**
     * Returns how long to wait for the daemon to answer.
     *
     * @return the timeout, always positive
     */
    Duration getResponseTimeout()
    {
        return this.responseTimeout;
    }

    /**
     * Returns the {@code Authorization} header to send to the daemon.
     *
     * @return the header value, or {@code null} when no daemon token is set
     */
    String getAuthorization()
    {
        return this.authorization;
    }
}
