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

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.sun.net.httpserver.HttpServer;
import io.uhndata.iap.documents.api.ParseService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParseAbandonment}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ParseAbandonmentTest
{
    private static final String JOB_ID = "86a4c102-4b6a-4933-bc33-cc02e0e26eb7";

    private static final String DOCUMENT = "/shared-docs/proposal.pdf";

    private final SlingContext context = new SlingContext();

    private final ParseService parses = Mockito.mock(ParseService.class);

    private ParseAbandonment abandonment;

    private HttpRequest sent;

    private IOException sendFailure;

    @BeforeEach
    void setUp() throws Exception
    {
        this.abandonment = new ParseAbandonment()
        {
            @Override
            protected HttpResponse<String> send(final HttpRequest request) throws IOException
            {
                ParseAbandonmentTest.this.sent = request;
                if (ParseAbandonmentTest.this.sendFailure != null) {
                    throw ParseAbandonmentTest.this.sendFailure;
                }
                return null;
            }

            @Override
            protected String environment(final String name)
            {
                return null;
            }
        };
        final Field factory = ParseAbandonment.class.getDeclaredField("resolverFactory");
        factory.setAccessible(true);
        factory.set(this.abandonment, new TestResolverFactory(this.context.resourceResolver()));
        final Field service = ParseAbandonment.class.getDeclaredField("parseService");
        service.setAccessible(true);
        service.set(this.abandonment, this.parses);
        this.abandonment.activate(Map.of());
    }

    @Test
    void stopsAnOpenParseAndDeletesItsRecord()
    {
        job(ParseJob.STATUS_QUEUED);

        this.abandonment.abandon(JOB_ID);

        assertEquals("http://localhost:18765/cancel?job_id=" + JOB_ID, this.sent.uri().toString());
        Mockito.verify(this.parses).discardStaging(DOCUMENT);
        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)));
    }

    @Test
    void leavesAFinishedParseForItsCallback()
    {
        job(ParseJob.STATUS_COMPLETED);

        this.abandonment.abandon(JOB_ID);

        assertEquals(ParseJob.STATUS_COMPLETED, this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID))
            .getValueMap().get(ParseJob.PN_STATUS, String.class));
        Mockito.verify(this.parses, Mockito.never()).discardStaging(Mockito.any());
    }

    @Test
    void ignoresSomethingThatIsNotAJob()
    {
        this.abandonment.abandon(null);
        this.abandonment.abandon("not-a-uuid");

        assertNull(this.sent);
    }

    @Test
    void stillForgetsTheJobWhenTheDaemonCannotBeReached()
    {
        job(ParseJob.STATUS_ACTIVE);
        this.sendFailure = new IOException("connection refused");

        this.abandonment.abandon(JOB_ID);

        Mockito.verify(this.parses).discardStaging(DOCUMENT);
        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)));
    }

    @Test
    void stillForgetsTheJobWhenTheCancelCallIsInterrupted()
    {
        job(ParseJob.STATUS_QUEUED);
        final ParseAbandonment interrupted = new ParseAbandonment()
        {
            @Override
            protected HttpResponse<String> send(final HttpRequest request) throws InterruptedException
            {
                throw new InterruptedException("stopped");
            }
        };
        try {
            inject(interrupted);
            interrupted.activate(Map.of());
            interrupted.abandon(JOB_ID);
            assertTrue(Thread.currentThread().isInterrupted());
            assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)));
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void sendsTheDaemonTokenWhenOneIsConfigured()
    {
        this.abandonment.activate(Map.of(ParseJob.DAEMON_TOKEN_PROPERTY, "s3cret"));
        job(ParseJob.STATUS_QUEUED);

        this.abandonment.abandon(JOB_ID);

        assertEquals("Bearer s3cret", this.sent.headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void doesNothingWhenTheRecordIsAlreadyGone()
    {
        this.abandonment.abandon(JOB_ID);

        assertEquals("http://localhost:18765/cancel?job_id=" + JOB_ID, this.sent.uri().toString());
        Mockito.verifyNoInteractions(this.parses);
    }

    @Test
    void talksRealHttpToTheDaemon() throws Exception
    {
        final HttpServer daemon = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            daemon.createContext("/cancel", exchange -> {
                final byte[] body = "{\"status\":\"cancelled\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            daemon.start();
            final ParseAbandonment real = new ParseAbandonment();
            inject(real);
            real.activate(Map.of("daemonUrl", "http://127.0.0.1:" + daemon.getAddress().getPort() + "/"));
            job(ParseJob.STATUS_QUEUED);

            real.abandon(JOB_ID);

            assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)));
        } finally {
            daemon.stop(0);
        }
    }

    @Test
    void aBlankDaemonUrlFallsBackToTheDefault()
    {
        this.abandonment.activate(Map.of("daemonUrl", "  "));
        job(ParseJob.STATUS_QUEUED);

        this.abandonment.abandon(JOB_ID);

        assertTrue(this.sent.uri().toString().startsWith("http://localhost:18765/cancel?"));
    }

    @Test
    void recordsTheFailureWhenTheJobsCannotBeOpened() throws Exception
    {
        final ResourceResolverFactory factory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(factory.getServiceResourceResolver(Mockito.anyMap())).thenThrow(new LoginException("no"));
        final Field field = ParseAbandonment.class.getDeclaredField("resolverFactory");
        field.setAccessible(true);
        field.set(this.abandonment, factory);
        job(ParseJob.STATUS_QUEUED);

        this.abandonment.abandon(JOB_ID);

        assertEquals(ParseJob.STATUS_QUEUED, this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID))
            .getValueMap().get(ParseJob.PN_STATUS, String.class));
    }

    private void job(final String status)
    {
        this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, status,
            ParseJob.PN_PATH, DOCUMENT);
    }

    private void inject(final ParseAbandonment target) throws ReflectiveOperationException
    {
        final Field factory = ParseAbandonment.class.getDeclaredField("resolverFactory");
        factory.setAccessible(true);
        factory.set(target, new TestResolverFactory(this.context.resourceResolver()));
        final Field service = ParseAbandonment.class.getDeclaredField("parseService");
        service.setAccessible(true);
        service.set(target, this.parses);
    }
}
