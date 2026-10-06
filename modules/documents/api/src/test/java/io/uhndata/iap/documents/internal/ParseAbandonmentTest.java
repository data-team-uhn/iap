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
import java.util.Calendar;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.sun.net.httpserver.HttpServer;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static final String TARGET = "/Submissions/ab/cd/ef/aRequest/d1/v1/file";

    private final SlingContext context = new SlingContext();

    private ParseAbandonment abandonment;

    private HttpRequest sent;

    private String statusWhenSent;

    private IOException sendFailure;

    private int status = 200;

    @BeforeEach
    void setUp() throws Exception
    {
        this.abandonment = new ParseAbandonment()
        {
            @Override
            protected CompletableFuture<HttpResponse<String>> sendAsync(final HttpRequest request)
            {
                ParseAbandonmentTest.this.sent = request;
                final Resource job = ParseAbandonmentTest.this.context.resourceResolver()
                    .getResource(ParseJob.nodePath(JOB_ID));
                ParseAbandonmentTest.this.statusWhenSent = job == null ? null
                    : job.getValueMap().get(ParseJob.PN_STATUS, String.class);
                if (ParseAbandonmentTest.this.sendFailure != null) {
                    return CompletableFuture.failedFuture(ParseAbandonmentTest.this.sendFailure);
                }
                @SuppressWarnings("unchecked")
                final HttpResponse<String> response = Mockito.mock(HttpResponse.class);
                Mockito.when(response.statusCode()).thenReturn(ParseAbandonmentTest.this.status);
                Mockito.when(response.body()).thenReturn("{}");
                return CompletableFuture.completedFuture(response);
            }

            @Override
            protected String environment(final String name)
            {
                return null;
            }
        };
        inject(this.abandonment, new TestResolverFactory(this.context.resourceResolver()));
        this.abandonment.activate(Map.of());
    }

    // The record keeps its target and its folder: the sweep hands the stop over, then drops both
    @Test
    void marksAnOpenParseStoppedAndKeepsItsRecord()
    {
        createJob(ParseJob.STATUS_QUEUED);

        assertFalse(this.abandonment.abandon(JOB_ID), "the record still names the folder");

        assertEquals("http://localhost:18765/cancel?job_id=" + JOB_ID, this.sent.uri().toString());
        // A dispatch that claims the job after this finds it stopped, and does not dispatch it
        assertEquals(ParseJob.STATUS_FAILED, this.statusWhenSent, "the record is marked before the daemon is asked");
        final ValueMap stopped = getRecord();
        assertEquals(ParseJob.STATUS_FAILED, stopped.get(ParseJob.PN_STATUS, String.class));
        assertEquals(ParseAbandonment.STOPPED, stopped.get(ParseJob.PN_ERROR, String.class));
        assertNotNull(stopped.get(ParseJob.PN_FINISHED, Calendar.class));
        assertEquals(TARGET, stopped.get(ParseJob.PN_TARGET, String.class), "the stop may yet be undone");
        assertEquals(DOCUMENT, stopped.get(ParseJob.PN_PATH, String.class));
    }

    @Test
    void dropsWhateverAStoppedParseHadProduced()
    {
        createJob(ParseJob.STATUS_ACTIVE).put(ParseJob.PN_OUTPUTS, new String[] { "/shared-docs/half.md" });

        this.abandonment.abandon(JOB_ID);

        assertArrayEquals(new String[0], getRecord().get(ParseJob.PN_OUTPUTS, new String[0]));
    }

    @Test
    void stillStopsTheJobWhenTheDaemonRefusesToStopIt()
    {
        createJob(ParseJob.STATUS_ACTIVE);
        this.status = 401;
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);
        try {
            this.abandonment.abandon(JOB_ID);

            Mockito.verify(recorder).logProblem(Mockito.eq("The document daemon refused to stop a parse"),
                Mockito.any());
        } finally {
            ErrorLogger.unsetService(recorder);
        }
        assertEquals(ParseJob.STATUS_FAILED, getRecord().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void stillStopsTheJobWhenTheDaemonCannotBeReached()
    {
        createJob(ParseJob.STATUS_ACTIVE);
        this.sendFailure = new IOException("connection refused");

        this.abandonment.abandon(JOB_ID);

        assertEquals(ParseJob.STATUS_FAILED, getRecord().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void readsHowLongToWaitForTheDaemon()
    {
        this.abandonment.activate(Map.of("responseTimeout", "5"));
        this.abandonment.activate(Map.of("responseTimeout", "-1"));
        this.abandonment.activate(Map.of("responseTimeout", "soon"));
        createJob(ParseJob.STATUS_QUEUED);

        this.abandonment.abandon(JOB_ID);

        assertEquals(ParseJob.STATUS_FAILED, getRecord().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void leavesAFinishedParseForItsCallback()
    {
        createJob(ParseJob.STATUS_COMPLETED);

        assertFalse(this.abandonment.abandon(JOB_ID), "the folder is not the caller's to remove");

        assertEquals(ParseJob.STATUS_COMPLETED, getRecord().get(ParseJob.PN_STATUS, String.class));
        assertNull(this.sent, "the daemon is done with it");
    }

    @Test
    void leavesAFailureTheDaemonAlreadyReported()
    {
        createJob(ParseJob.STATUS_FAILED).put(ParseJob.PN_ERROR, "The daemon answered HTTP 500");

        assertFalse(this.abandonment.abandon(JOB_ID));

        assertEquals("The daemon answered HTTP 500", getRecord().get(ParseJob.PN_ERROR, String.class));
    }

    // The callback recorded an outcome between the read and the commit: a normal race, so nothing is recorded
    @Test
    void givesWayToAnOutcomeThatGotThereFirst() throws Exception
    {
        createJob(ParseJob.STATUS_ACTIVE);
        this.context.resourceResolver().commit();
        final AtomicBoolean reverted = new AtomicBoolean();
        inject(this.abandonment, new TestResolverFactory(new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public void commit() throws PersistenceException
            {
                throw new PersistenceException("the callback changed it first");
            }

            @Override
            public void revert()
            {
                reverted.set(true);
                super.revert();
            }
        }));
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);
        try {
            assertFalse(this.abandonment.abandon(JOB_ID), "the record still names the folder");

            Mockito.verifyNoInteractions(recorder);
            assertNotNull(this.sent, "the record may have moved to active, so the daemon is still asked");
        } finally {
            ErrorLogger.unsetService(recorder);
        }
        assertTrue(reverted.get(), "nothing half written is left in the session");
    }

    @Test
    void ignoresSomethingThatIsNotAJob()
    {
        assertTrue(this.abandonment.abandon(null));
        assertTrue(this.abandonment.abandon("not-a-uuid"));

        assertNull(this.sent);
    }

    @Test
    void sendsTheDaemonTokenWhenOneIsConfigured()
    {
        this.abandonment.activate(Map.of(ParseJob.DAEMON_TOKEN_PROPERTY, "s3cret"));
        createJob(ParseJob.STATUS_QUEUED);

        this.abandonment.abandon(JOB_ID);

        assertEquals("Bearer s3cret", this.sent.headers().firstValue("Authorization").orElseThrow());
    }

    // Nothing names the folder any more, so it is the caller's to remove
    @Test
    void handsTheFolderBackWhenTheRecordIsAlreadyGone()
    {
        assertTrue(this.abandonment.abandon(JOB_ID));

        assertEquals("http://localhost:18765/cancel?job_id=" + JOB_ID, this.sent.uri().toString());
    }

    @Test
    void talksRealHttpToTheDaemon() throws Exception
    {
        final HttpServer daemon = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final CountDownLatch asked = new CountDownLatch(1);
        try {
            daemon.createContext("/cancel", exchange -> {
                final byte[] body = "{\"status\":\"cancelled\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                asked.countDown();
            });
            daemon.start();
            final ParseAbandonment real = new ParseAbandonment();
            inject(real, new TestResolverFactory(this.context.resourceResolver()));
            real.activate(Map.of("daemonUrl", "http://127.0.0.1:" + daemon.getAddress().getPort() + "/"));
            createJob(ParseJob.STATUS_QUEUED);

            real.abandon(JOB_ID);

            assertTrue(asked.await(10, TimeUnit.SECONDS), "the daemon was asked");
            assertEquals(ParseJob.STATUS_FAILED, getRecord().get(ParseJob.PN_STATUS, String.class));
        } finally {
            daemon.stop(0);
        }
    }

    @Test
    void aBlankDaemonUrlFallsBackToTheDefault()
    {
        this.abandonment.activate(Map.of("daemonUrl", "  "));
        createJob(ParseJob.STATUS_QUEUED);

        this.abandonment.abandon(JOB_ID);

        assertTrue(this.sent.uri().toString().startsWith("http://localhost:18765/cancel?"));
    }

    @Test
    void recordsTheFailureWhenTheJobsCannotBeOpened() throws Exception
    {
        final ResourceResolverFactory factory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(factory.getServiceResourceResolver(Mockito.anyMap())).thenThrow(new LoginException("no"));
        inject(this.abandonment, factory);
        createJob(ParseJob.STATUS_QUEUED);

        assertFalse(this.abandonment.abandon(JOB_ID), "unknown, so the folder is not the caller's to remove");

        assertEquals(ParseJob.STATUS_QUEUED, getRecord().get(ParseJob.PN_STATUS, String.class));
    }

    private ModifiableValueMap createJob(final String jobStatus)
    {
        return this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, jobStatus,
            ParseJob.PN_PATH, DOCUMENT,
            ParseJob.PN_TARGET, TARGET).adaptTo(ModifiableValueMap.class);
    }

    private ValueMap getRecord()
    {
        return this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)).getValueMap();
    }

    private static void inject(final ParseAbandonment target, final ResourceResolverFactory factory)
        throws ReflectiveOperationException
    {
        final Field field = ParseAbandonment.class.getDeclaredField("resolverFactory");
        field.setAccessible(true);
        field.set(target, factory);
    }
}
