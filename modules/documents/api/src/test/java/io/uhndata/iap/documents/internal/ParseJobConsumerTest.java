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
import java.time.Duration;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer.JobResult;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParseJobConsumer}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ParseJobConsumerTest
{
    private static final String JOB_ID = "86a4c102-4b6a-4933-bc33-cc02e0e26eb7";

    private static final String DOCUMENT = "/shared-docs/proposal.pdf";

    private static final String TOKEN = "test-callback-token";

    /** What the daemon answers when it accepts an asynchronous parse. */
    private static final String ACCEPTED_BODY = "{\"job_id\": \"" + JOB_ID + "\", \"status\": \"queued\"}";

    /** What an older daemon answers when it ignores job_id/callback and runs synchronously. */
    private static final String SYNC_SUCCESS_BODY = "{\"ok\": true,"
        + " \"markdown_path\": \"/shared-docs/proposal.md\", \"chunked\": false, \"chunks_dir\": null}";

    private final SlingContext context = new SlingContext();

    @SuppressWarnings("unchecked")
    private final HttpResponse<String> daemonResponse = Mockito.mock(HttpResponse.class);

    private final Job job = Mockito.mock(Job.class);

    private ParseJobConsumer consumer;

    private HttpRequest sentRequest;

    private IOException sendFailure;

    @BeforeEach
    void setUp() throws Exception
    {
        this.sentRequest = null;
        this.sendFailure = null;
        this.consumer = consumerWithEnvironment(null);
        inject(this.consumer, new TestResolverFactory(this.context.resourceResolver()));
        activate(this.consumer);
        Mockito.when(this.job.getProperty(ParseJob.PN_JOB_ID, String.class)).thenReturn(JOB_ID);
    }

    @Test
    void acceptedDispatchLeavesTheJobActive()
    {
        jobNode(Boolean.TRUE);
        daemonAnswers(202, ACCEPTED_BODY);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        final ValueMap properties = jobProperties();
        assertEquals(ParseJob.STATUS_ACTIVE, properties.get(ParseJob.PN_STATUS, String.class));
        assertNotNull(properties.get(ParseJob.PN_STARTED, Calendar.class));
        assertNull(properties.get(ParseJob.PN_FINISHED, Calendar.class));
        assertEquals("http://localhost:18765/parse?path=%2Fshared-docs%2Fproposal.pdf&chunk=true"
            + "&job_id=" + JOB_ID
            + "&callback=http%3A%2F%2Fhost.docker.internal%3A8080%2Fsystem%2Fdocuments%2FparseCallback",
            this.sentRequest.uri().toString());
        assertEquals(Duration.ofSeconds(30), this.sentRequest.timeout().orElseThrow());
    }

    @Test
    void plainOkDispatchIsAcceptedToo()
    {
        jobNode(Boolean.FALSE);
        daemonAnswers(200, ACCEPTED_BODY);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(ParseJob.STATUS_ACTIVE, jobProperties().get(ParseJob.PN_STATUS, String.class));
        assertTrue(this.sentRequest.uri().toString().contains("&chunk=false&"));
    }

    @Test
    void synchronousSuccessBodyFailsTheJob()
    {
        jobNode(Boolean.TRUE);
        daemonAnswers(200, SYNC_SUCCESS_BODY);

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        final ValueMap properties = jobProperties();
        assertEquals(ParseJob.STATUS_FAILED, properties.get(ParseJob.PN_STATUS, String.class));
        assertTrue(properties.get(ParseJob.PN_ERROR, String.class)
            .contains("did not accept the asynchronous parse"));
        assertNotNull(properties.get(ParseJob.PN_FINISHED, Calendar.class));
    }

    @Test
    void acceptBodyForAnotherJobIsRefused()
    {
        jobNode(Boolean.TRUE);
        daemonAnswers(202, "{\"job_id\": \"00000000-0000-0000-0000-000000000000\", \"status\": \"queued\"}");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ParseJob.STATUS_FAILED, jobProperties().get(ParseJob.PN_STATUS, String.class));
        assertNotNull(this.sentRequest);
    }

    @Test
    void emptyAcceptBodyFailsTheJob()
    {
        jobNode(Boolean.TRUE);
        daemonAnswers(202, "");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ParseJob.STATUS_FAILED, jobProperties().get(ParseJob.PN_STATUS, String.class));
        assertEquals("The daemon did not accept the asynchronous parse: (empty response)",
            jobProperties().get(ParseJob.PN_ERROR, String.class));
    }

    @Test
    void nonJsonAcceptBodyFailsTheJob()
    {
        jobNode(Boolean.TRUE);
        daemonAnswers(200, "queued, thanks");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ParseJob.STATUS_FAILED, jobProperties().get(ParseJob.PN_STATUS, String.class));
        assertTrue(jobProperties().get(ParseJob.PN_ERROR, String.class).contains("queued, thanks"));
    }

    @Test
    void missingCallbackTokenFailsWithoutCallingTheDaemon() throws Exception
    {
        final ParseJobConsumer unconfigured = consumerWithEnvironment(null);
        inject(unconfigured, new TestResolverFactory(this.context.resourceResolver()));
        unconfigured.activate(Map.of());
        jobNode(Boolean.TRUE);

        assertEquals(JobResult.CANCEL, unconfigured.process(this.job));

        assertEquals(ParseJob.STATUS_FAILED, jobProperties().get(ParseJob.PN_STATUS, String.class));
        assertEquals("Callback authentication is not configured",
            jobProperties().get(ParseJob.PN_ERROR, String.class));
        assertNull(this.sentRequest);
    }

    @Test
    void theTokenCanComeFromTheEnvironment() throws Exception
    {
        final ParseJobConsumer fromEnvironment = consumerWithEnvironment("  " + TOKEN + "  ");
        inject(fromEnvironment, new TestResolverFactory(this.context.resourceResolver()));
        fromEnvironment.activate(Map.of());
        jobNode(Boolean.TRUE);
        daemonAnswers(202, ACCEPTED_BODY);

        assertEquals(JobResult.OK, fromEnvironment.process(this.job));

        assertEquals(ParseJob.STATUS_ACTIVE, jobProperties().get(ParseJob.PN_STATUS, String.class));
        assertNotNull(this.sentRequest);
    }

    @Test
    void refusedDispatchMarksTheJobFailed()
    {
        jobNode(Boolean.TRUE);
        daemonAnswers(400, "{\"error\": \"path must be under /shared-docs\"}");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        final ValueMap properties = jobProperties();
        assertEquals(ParseJob.STATUS_FAILED, properties.get(ParseJob.PN_STATUS, String.class));
        assertEquals("The daemon answered HTTP 400: path must be under /shared-docs",
            properties.get(ParseJob.PN_ERROR, String.class));
        assertNotNull(properties.get(ParseJob.PN_FINISHED, Calendar.class));
    }

    @Test
    void nonJsonRefusalIsExcerpted()
    {
        jobNode(Boolean.TRUE);
        daemonAnswers(500, "x".repeat(250));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        final String error = jobProperties().get(ParseJob.PN_ERROR, String.class);
        assertTrue(error.contains("HTTP 500"));
        assertTrue(error.endsWith("…"));
        assertTrue(error.length() < 250);
    }

    @Test
    void jsonRefusalWithoutAMessageKeepsTheBody()
    {
        jobNode(Boolean.TRUE);
        daemonAnswers(502, "{\"status\": \"broken\"}");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertTrue(jobProperties().get(ParseJob.PN_ERROR, String.class).contains("{\"status\": \"broken\"}"));
    }

    @Test
    void emptyRefusalIsStillRecorded()
    {
        jobNode(Boolean.TRUE);
        daemonAnswers(503, "");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals("The daemon answered HTTP 503: (empty response)",
            jobProperties().get(ParseJob.PN_ERROR, String.class));
    }

    @Test
    void unreachableDaemonMarksTheJobFailed()
    {
        jobNode(Boolean.TRUE);
        this.sendFailure = new IOException("Connection refused");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        final ValueMap properties = jobProperties();
        assertEquals(ParseJob.STATUS_FAILED, properties.get(ParseJob.PN_STATUS, String.class));
        assertTrue(properties.get(ParseJob.PN_ERROR, String.class).contains("Connection refused"));
    }

    @Test
    void interruptedDispatchMarksTheJobFailed() throws Exception
    {
        jobNode(Boolean.TRUE);
        final ParseJobConsumer interrupted = new ParseJobConsumer()
        {
            @Override
            protected HttpResponse<String> send(final HttpRequest request) throws InterruptedException
            {
                throw new InterruptedException();
            }
        };
        inject(interrupted, new TestResolverFactory(this.context.resourceResolver()));
        activate(interrupted);

        assertEquals(JobResult.CANCEL, interrupted.process(this.job));

        // The interruption must be passed on; reading it also clears it, keeping the test worker usable
        assertTrue(Thread.interrupted());
        final ValueMap properties = jobProperties();
        assertEquals(ParseJob.STATUS_FAILED, properties.get(ParseJob.PN_STATUS, String.class));
        assertTrue(properties.get(ParseJob.PN_ERROR, String.class).contains("Interrupted"));
    }

    @Test
    void jobWithoutANodeIsDropped()
    {
        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));
    }

    @Test
    void jobWithoutAnIdentifierIsDropped()
    {
        Mockito.when(this.job.getProperty(ParseJob.PN_JOB_ID, String.class)).thenReturn(null);

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));
    }

    @Test
    void jobWithABlankPathIsFailed()
    {
        this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, ParseJob.STATUS_QUEUED,
            ParseJob.PN_PATH, " ");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ParseJob.STATUS_FAILED, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void missingServiceUserCancelsTheJob() throws Exception
    {
        jobNode(Boolean.TRUE);
        inject(this.consumer, new TestResolverFactory(null));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ParseJob.STATUS_QUEUED, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void serviceUserVanishingAfterTheDispatchLosesOnlyTheRecord() throws Exception
    {
        jobNode(Boolean.TRUE);
        daemonAnswers(400, "{\"error\": \"boom\"}");
        inject(this.consumer, failAfterFirstOpen());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        // The failure could not be recorded, so the node still says active
        assertEquals(ParseJob.STATUS_ACTIVE, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void vanishedJobNodeCannotRecordTheFailure() throws Exception
    {
        jobNode(Boolean.TRUE);
        daemonAnswers(400, "{\"error\": \"boom\"}");
        inject(this.consumer, secondOpenFindsNothing());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ParseJob.STATUS_ACTIVE, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void unmodifiableJobNodeCancelsTheJob() throws Exception
    {
        jobNode(Boolean.TRUE);
        inject(this.consumer, new TestResolverFactory(unmodifiableNodes()));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ParseJob.STATUS_QUEUED, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void honoursTheConfiguredUrlsAndTimeout()
    {
        activate(this.consumer, Map.of("daemonUrl", "http://docling:9999/",
            "callbackUrl", "http://iap:8080/system/documents/parseCallback/", "responseTimeout", "5"));
        jobNode(Boolean.TRUE);
        daemonAnswers(202, ACCEPTED_BODY);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        final String uri = this.sentRequest.uri().toString();
        assertTrue(uri.startsWith("http://docling:9999/parse?"));
        assertTrue(uri.endsWith("&callback=http%3A%2F%2Fiap%3A8080%2Fsystem%2Fdocuments%2FparseCallback"));
        assertEquals(Duration.ofSeconds(5), this.sentRequest.timeout().orElseThrow());
    }

    @Test
    void nonsenseConfigurationFallsBackToTheDefaults()
    {
        activate(this.consumer, Map.of("daemonUrl", "", "callbackUrl", " ", "responseTimeout", "soon"));
        jobNode(Boolean.TRUE);
        daemonAnswers(202, ACCEPTED_BODY);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertTrue(this.sentRequest.uri().toString().startsWith("http://localhost:18765/parse?"));
        assertTrue(this.sentRequest.uri().toString().contains("host.docker.internal"));
        assertEquals(Duration.ofSeconds(30), this.sentRequest.timeout().orElseThrow());
    }

    @Test
    void nonPositiveTimeoutFallsBackToTheDefault()
    {
        activate(this.consumer, Map.of("responseTimeout", "-5"));
        jobNode(Boolean.TRUE);
        daemonAnswers(202, ACCEPTED_BODY);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(Duration.ofSeconds(30), this.sentRequest.timeout().orElseThrow());
    }

    @Test
    void talksRealHttpToTheDaemon() throws Exception
    {
        final HttpServer daemon = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            daemon.createContext("/parse", exchange -> {
                final byte[] body = ACCEPTED_BODY.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            daemon.start();
            // No overridden send(): this consumer speaks to the local stand-in over an actual socket
            final ParseJobConsumer real = new ParseJobConsumer();
            inject(real, new TestResolverFactory(this.context.resourceResolver()));
            activate(real, Map.of("daemonUrl", "http://127.0.0.1:" + daemon.getAddress().getPort()));
            jobNode(Boolean.TRUE);

            assertEquals(JobResult.OK, real.process(this.job));

            assertEquals(ParseJob.STATUS_ACTIVE, jobProperties().get(ParseJob.PN_STATUS, String.class));
        } finally {
            daemon.stop(0);
        }
    }

    /**
     * Build a consumer whose {@code send} is stubbed and whose environment lookup answers with the given value.
     *
     * @param environmentToken what {@code IAP_DOCLING_CALLBACK_JWT} should appear to hold, {@code null} for unset
     * @return a consumer ready for {@link #inject} and {@link #activate}
     */
    private ParseJobConsumer consumerWithEnvironment(final String environmentToken)
    {
        return new ParseJobConsumer()
        {
            @Override
            protected HttpResponse<String> send(final HttpRequest request) throws IOException
            {
                ParseJobConsumerTest.this.sentRequest = request;
                if (ParseJobConsumerTest.this.sendFailure != null) {
                    throw ParseJobConsumerTest.this.sendFailure;
                }
                return ParseJobConsumerTest.this.daemonResponse;
            }

            @Override
            protected String environment(final String name)
            {
                return ParseJob.TOKEN_VARIABLE.equals(name) ? environmentToken : null;
            }
        };
    }

    private void activate(final ParseJobConsumer target)
    {
        activate(target, Map.of());
    }

    private void activate(final ParseJobConsumer target, final Map<String, Object> overrides)
    {
        final Map<String, Object> configuration = new HashMap<>(overrides);
        configuration.putIfAbsent(ParseJob.TOKEN_PROPERTY, TOKEN);
        target.activate(configuration);
    }

    private void inject(final ParseJobConsumer target, final ResourceResolverFactory factory) throws Exception
    {
        final Field reference = ParseJobConsumer.class.getDeclaredField("resolverFactory");
        reference.setAccessible(true);
        reference.set(target, factory);
    }

    private void jobNode(final Boolean chunk)
    {
        this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, ParseJob.STATUS_QUEUED,
            ParseJob.PN_PATH, DOCUMENT,
            ParseJob.PN_CHUNK, chunk);
    }

    private ValueMap jobProperties()
    {
        return this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)).getValueMap();
    }

    private void daemonAnswers(final int status, final String body)
    {
        Mockito.when(this.daemonResponse.statusCode()).thenReturn(status);
        Mockito.when(this.daemonResponse.body()).thenReturn(body);
    }

    /**
     * A factory whose service user disappears once the job is marked active, so recording the outcome fails.
     *
     * @return a factory answering only its first request
     */
    private ResourceResolverFactory failAfterFirstOpen()
    {
        return new TestResolverFactory(this.context.resourceResolver())
        {
            private boolean opened;

            @Override
            public ResourceResolver getServiceResourceResolver(final Map<String, Object> authenticationInfo)
                throws LoginException
            {
                if (this.opened) {
                    throw new LoginException("The service user is gone");
                }
                this.opened = true;
                return super.getServiceResourceResolver(authenticationInfo);
            }
        };
    }

    /**
     * A factory whose second session no longer sees the job node, as if it was deleted while the daemon worked.
     *
     * @return a factory whose sessions after the first find nothing
     */
    private ResourceResolverFactory secondOpenFindsNothing()
    {
        return new TestResolverFactory(this.context.resourceResolver())
        {
            private boolean opened;

            @Override
            public ResourceResolver getServiceResourceResolver(final Map<String, Object> authenticationInfo)
                throws LoginException
            {
                final ResourceResolver resolver = super.getServiceResourceResolver(authenticationInfo);
                if (!this.opened) {
                    this.opened = true;
                    return resolver;
                }
                return new ResourceResolverWrapper(resolver)
                {
                    @Override
                    public Resource getResource(final String path)
                    {
                        return null;
                    }
                };
            }
        };
    }

    /**
     * A resolver whose resources refuse to be adapted for editing.
     *
     * @return a resolver handing out read-only resources
     */
    private ResourceResolver unmodifiableNodes()
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource resource = super.getResource(path);
                if (resource == null) {
                    return null;
                }
                final Resource spy = Mockito.spy(resource);
                Mockito.doReturn(null).when(spy).adaptTo(ModifiableValueMap.class);
                return spy;
            }
        };
    }
}
