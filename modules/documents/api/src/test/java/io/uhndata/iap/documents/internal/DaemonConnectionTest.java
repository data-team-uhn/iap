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

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DaemonConnection}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DaemonConnectionTest
{
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(DaemonConnection.DEFAULT_RESPONSE_TIMEOUT);

    @Test
    void worksWithNothingConfigured()
    {
        final DaemonConnection daemon = DaemonConnection.read(Map.of(), name -> null);

        assertEquals(DaemonConnection.DEFAULT_URL, daemon.getUrl());
        assertEquals(DEFAULT_TIMEOUT, daemon.getResponseTimeout());
        assertNull(daemon.getAuthorization());
    }

    @Test
    void trimsTrailingSlashesFromTheUrl()
    {
        assertEquals("http://docling:9999",
            DaemonConnection.read(Map.of("daemonUrl", "http://docling:9999//"), name -> null).getUrl());
    }

    @Test
    void usesTheDefaultForABlankUrl()
    {
        assertEquals(DaemonConnection.DEFAULT_URL,
            DaemonConnection.read(Map.of("daemonUrl", " "), name -> null).getUrl());
    }

    @Test
    void readsTheTimeoutAsAnyNumberType()
    {
        assertEquals(Duration.ofSeconds(5),
            DaemonConnection.read(Map.of("responseTimeout", "5"), name -> null).getResponseTimeout());
        assertEquals(Duration.ofSeconds(7),
            DaemonConnection.read(Map.of("responseTimeout", 7L), name -> null).getResponseTimeout());
    }

    @Test
    void usesTheDefaultForAnUnusableTimeout()
    {
        assertEquals(DEFAULT_TIMEOUT,
            DaemonConnection.read(Map.of("responseTimeout", "soon"), name -> null).getResponseTimeout());
        assertEquals(DEFAULT_TIMEOUT,
            DaemonConnection.read(Map.of("responseTimeout", "-1"), name -> null).getResponseTimeout());
    }

    @Test
    void prefersTheConfiguredTokenOverTheEnvironment()
    {
        final DaemonConnection daemon = DaemonConnection.read(Map.of(ParseJob.DAEMON_TOKEN_PROPERTY, " configured "),
            name -> "from-environment");

        assertEquals("Bearer configured", daemon.getAuthorization());
    }

    @Test
    void fallsBackToTheEnvironmentToken()
    {
        assertEquals("Bearer from-environment", DaemonConnection.read(Map.of(ParseJob.DAEMON_TOKEN_PROPERTY, ""),
            name -> ParseJob.DAEMON_TOKEN_VARIABLE.equals(name) ? " from-environment " : null).getAuthorization());
    }

    @Test
    void buildsAPostCarryingTheTimeoutAndTheToken()
    {
        final HttpRequest request = DaemonConnection.read(Map.of("daemonUrl", "http://docling:9999/",
            "responseTimeout", "5", ParseJob.DAEMON_TOKEN_PROPERTY, "secret"), name -> null).post("/cancel?job_id=j1");

        assertEquals("POST", request.method());
        assertEquals("http://docling:9999/cancel?job_id=j1", request.uri().toString());
        assertEquals(Duration.ofSeconds(5), request.timeout().orElseThrow());
        assertEquals("Bearer secret", request.headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void buildsAPostWithNoTokenWhenTheDaemonNeedsNone()
    {
        final HttpRequest request = DaemonConnection.read(Map.of(), name -> null).post("/parse?path=x");

        assertTrue(request.headers().firstValue("Authorization").isEmpty());
    }

    @Test
    void sendsNoTokenWhenTheEnvironmentHoldsOnlySpaces()
    {
        assertNull(DaemonConnection.read(Map.of(), name -> "  ").getAuthorization());
    }
}
