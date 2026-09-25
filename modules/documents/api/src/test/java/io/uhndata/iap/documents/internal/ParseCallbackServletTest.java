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

import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.documents.api.ParseOutcome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParseCallbackServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ParseCallbackServletTest
{
    private static final String JOB_ID = "86a4c102-4b6a-4933-bc33-cc02e0e26eb7";

    private static final String TOKEN = "secret-jwt";

    private static final String GOOD_AUTHORIZATION = "Bearer " + TOKEN;

    /** What the daemon POSTs after a successful parse. */
    private static final String SUCCESS_BODY = "{\"job_id\": \"" + JOB_ID + "\", \"ok\": true,"
        + " \"markdown_path\": \"/shared-docs/proposal.md\", \"tokens\": 12000,"
        + " \"filename\": \"proposal.pdf\"}";

    private final SlingContext context = new SlingContext();

    private final ParseOutcomeDispatcher dispatcher = new ParseOutcomeDispatcher();

    private ParseCallbackServlet servlet;

    @BeforeEach
    void setUp() throws Exception
    {
        this.servlet = servletWithEnvironment(null);
        this.servlet.activate(Map.of(ParseJob.TOKEN_PROPERTY, TOKEN));
    }

    @Test
    void successOutcomeCompletesTheJob() throws Exception
    {
        jobNode();

        final MockSlingJakartaHttpServletResponse response = post(GOOD_AUTHORIZATION, SUCCESS_BODY);

        assertEquals(200, response.getStatus());
        final JsonObject answer = parse(response);
        assertEquals(JOB_ID, answer.getString("job_id"));
        assertEquals(ParseJob.STATUS_COMPLETED, answer.getString("status"));
        final ValueMap properties = jobProperties();
        assertEquals(ParseJob.STATUS_COMPLETED, properties.get(ParseJob.PN_STATUS, String.class));
        assertArrayEquals(new String[] { "/shared-docs/proposal.md" },
            properties.get(ParseJob.PN_OUTPUTS, String[].class));
        assertNotNull(properties.get(ParseJob.PN_FINISHED));
    }

    @Test
    void failureOutcomeFailsTheJob() throws Exception
    {
        jobNode();

        final MockSlingJakartaHttpServletResponse response = post(GOOD_AUTHORIZATION,
            "{\"job_id\": \"" + JOB_ID + "\", \"ok\": false, \"error\": \"cannot parse\"}");

        assertEquals(200, response.getStatus());
        assertEquals(ParseJob.STATUS_FAILED, parse(response).getString("status"));
        final ValueMap properties = jobProperties();
        assertEquals(ParseJob.STATUS_FAILED, properties.get(ParseJob.PN_STATUS, String.class));
        assertEquals("cannot parse", properties.get(ParseJob.PN_ERROR, String.class));
        assertNull(properties.get(ParseJob.PN_OUTPUTS, String[].class));
    }

    @Test
    void aSuccessClearsAnEarlierError() throws Exception
    {
        // A dispatch that timed out while the daemon went on parsing leaves an error behind
        this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, ParseJob.STATUS_FAILED,
            ParseJob.PN_PATH, "/shared-docs/proposal.pdf",
            ParseJob.PN_ERROR, "Calling the daemon failed: null");

        final MockSlingJakartaHttpServletResponse response = post(GOOD_AUTHORIZATION, SUCCESS_BODY);

        assertEquals(200, response.getStatus());
        final ValueMap properties = jobProperties();
        assertEquals(ParseJob.STATUS_COMPLETED, properties.get(ParseJob.PN_STATUS, String.class));
        assertNull(properties.get(ParseJob.PN_ERROR, String.class));
    }

    @Test
    void aFailureClearsEarlierOutputs() throws Exception
    {
        this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, ParseJob.STATUS_COMPLETED,
            ParseJob.PN_PATH, "/shared-docs/proposal.pdf",
            ParseJob.PN_OUTPUTS, new String[] { "/shared-docs/proposal.md" });

        final MockSlingJakartaHttpServletResponse response = post(GOOD_AUTHORIZATION,
            "{\"job_id\": \"" + JOB_ID + "\", \"ok\": false, \"error\": \"cannot parse\"}");

        assertEquals(200, response.getStatus());
        final ValueMap properties = jobProperties();
        assertEquals(ParseJob.STATUS_FAILED, properties.get(ParseJob.PN_STATUS, String.class));
        assertEquals("cannot parse", properties.get(ParseJob.PN_ERROR, String.class));
        assertNull(properties.get(ParseJob.PN_OUTPUTS, String[].class));
    }

    @Test
    void failureWithoutAMessageGetsADefault() throws Exception
    {
        jobNode();

        post(GOOD_AUTHORIZATION, "{\"job_id\": \"" + JOB_ID + "\", \"ok\": false}");

        assertEquals("The daemon reported a failure without details",
            jobProperties().get(ParseJob.PN_ERROR, String.class));
    }

    @Test
    void unconfiguredTokenRefusesEveryDelivery() throws Exception
    {
        final ParseCallbackServlet unconfigured = servletWithEnvironment(null);
        unconfigured.activate(Map.of());
        jobNode();

        final MockSlingJakartaHttpServletResponse response = call(unconfigured, GOOD_AUTHORIZATION, SUCCESS_BODY);

        assertEquals(503, response.getStatus());
        assertEquals(ParseJob.STATUS_QUEUED, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void theTokenCanComeFromTheEnvironment() throws Exception
    {
        final ParseCallbackServlet fromEnvironment = servletWithEnvironment("  " + TOKEN + "  ");
        fromEnvironment.activate(Map.of());
        jobNode();

        assertEquals(200, call(fromEnvironment, GOOD_AUTHORIZATION, SUCCESS_BODY).getStatus());
    }

    @Test
    void theConfiguredTokenOverridesTheEnvironment() throws Exception
    {
        final ParseCallbackServlet configured = servletWithEnvironment("environment-token");
        configured.activate(Map.of(ParseJob.TOKEN_PROPERTY, TOKEN));
        jobNode();

        assertEquals(401, call(configured, "Bearer environment-token", SUCCESS_BODY).getStatus());
        assertEquals(200, call(configured, GOOD_AUTHORIZATION, SUCCESS_BODY).getStatus());
    }

    @Test
    void missingAuthorizationIsRefused() throws Exception
    {
        jobNode();

        final MockSlingJakartaHttpServletResponse response = post(null, SUCCESS_BODY);

        assertEquals(401, response.getStatus());
        assertEquals(ParseJob.STATUS_QUEUED, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void wrongTokenIsRefused() throws Exception
    {
        jobNode();

        assertEquals(401, post("Bearer forged", SUCCESS_BODY).getStatus());
        assertEquals(ParseJob.STATUS_QUEUED, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void aBareTokenWithoutTheBearerSchemeIsRefused() throws Exception
    {
        jobNode();

        assertEquals(401, post(TOKEN, SUCCESS_BODY).getStatus());
    }

    @Test
    void malformedBodyIsRejected() throws Exception
    {
        jobNode();

        assertEquals(400, post(GOOD_AUTHORIZATION, "this is not JSON").getStatus());
        assertEquals(ParseJob.STATUS_QUEUED, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void missingJobIdIsRejected() throws Exception
    {
        assertEquals(400, post(GOOD_AUTHORIZATION, "{\"ok\": true}").getStatus());
    }

    @Test
    void malformedJobIdIsRejected() throws Exception
    {
        assertEquals(400,
            post(GOOD_AUTHORIZATION, "{\"job_id\": \"../../oops\", \"ok\": true}").getStatus());
    }

    @Test
    void successWithoutAMarkdownPathIsRejected() throws Exception
    {
        jobNode();

        final MockSlingJakartaHttpServletResponse response =
            post(GOOD_AUTHORIZATION, "{\"job_id\": \"" + JOB_ID + "\", \"ok\": true}");

        assertEquals(400, response.getStatus());
        assertTrue(parse(response).getString("error").contains("markdown_path"));
        assertEquals(ParseJob.STATUS_QUEUED, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void unknownJobIsNotFound() throws Exception
    {
        final MockSlingJakartaHttpServletResponse response = post(GOOD_AUTHORIZATION,
            "{\"job_id\": \"" + UUID.randomUUID() + "\", \"ok\": false, \"error\": \"boom\"}");

        assertEquals(404, response.getStatus());
    }

    @Test
    void aNodeBelongingToAnotherJobIsNotFound() throws Exception
    {
        this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, UUID.randomUUID().toString(),
            ParseJob.PN_STATUS, ParseJob.STATUS_QUEUED);

        assertEquals(404, post(GOOD_AUTHORIZATION, SUCCESS_BODY).getStatus());
    }

    @Test
    void anUnmodifiableJobNodeIsReportedMissing() throws Exception
    {
        jobNode();
        inject(this.servlet, new TestResolverFactory(unmodifiableNodes()));

        assertEquals(404, post(GOOD_AUTHORIZATION, SUCCESS_BODY).getStatus());
    }

    @Test
    void everythingFailsWithoutTheServiceUser() throws Exception
    {
        jobNode();
        inject(this.servlet, new TestResolverFactory(null));

        assertEquals(500, post(GOOD_AUTHORIZATION, SUCCESS_BODY).getStatus());
    }

    @Test
    void aFailedCommitIsAServerError() throws Exception
    {
        jobNode();
        inject(this.servlet, new TestResolverFactory(failingCommits()));

        final MockSlingJakartaHttpServletResponse response = post(GOOD_AUTHORIZATION, SUCCESS_BODY);

        assertEquals(500, response.getStatus());
        assertTrue(parse(response).getString("error").contains("could not be recorded"));
    }

    @Test
    void theRealEnvironmentLookupIsHarmlessWhenConfigured() throws Exception
    {
        // No overridden environment(): the real lookup runs, and the configured token keeps the outcome
        // deterministic whether or not the variable is set on the machine running the tests
        final ParseCallbackServlet real = new ParseCallbackServlet();
        inject(real, new TestResolverFactory(this.context.resourceResolver()));
        real.activate(Map.of(ParseJob.TOKEN_PROPERTY, TOKEN));
        jobNode();

        assertEquals(200, call(real, GOOD_AUTHORIZATION, SUCCESS_BODY).getStatus());
    }

    /**
     * Build a servlet whose environment lookup answers with the given value, so tests never depend on the real
     * environment.
     *
     * @param token what the {@code IAP_DOCLING_CALLBACK_JWT} variable should appear to hold, {@code null} for unset
     * @return a servlet wired to the test repository
     */
    private ParseCallbackServlet servletWithEnvironment(final String token) throws Exception
    {
        final ParseCallbackServlet built = new ParseCallbackServlet()
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected String environment(final String name)
            {
                return ParseJob.TOKEN_VARIABLE.equals(name) ? token : null;
            }
        };
        inject(built, new TestResolverFactory(this.context.resourceResolver()));
        return built;
    }

    private void inject(final ParseCallbackServlet target, final ResourceResolverFactory factory) throws Exception
    {
        final Field reference = ParseCallbackServlet.class.getDeclaredField("resolverFactory");
        reference.setAccessible(true);
        reference.set(target, factory);
        final Field outcomes = ParseCallbackServlet.class.getDeclaredField("outcomes");
        outcomes.setAccessible(true);
        outcomes.set(target, this.dispatcher);
    }

    @Test
    void aTargetedOutcomeIsHandedOverAndItsRecordDropped() throws Exception
    {
        final List<ParseOutcome> handed = new ArrayList<>();
        this.dispatcher.bindHandler(outcome -> {
            handed.add(outcome);
            return true;
        });
        this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, ParseJob.STATUS_ACTIVE,
            ParseJob.PN_PATH, "/shared-docs/proposal.pdf",
            ParseJob.PN_TARGET, "/Submissions/aRequest/d1/v1/file");

        final MockSlingJakartaHttpServletResponse response = post(GOOD_AUTHORIZATION, SUCCESS_BODY);

        assertEquals(200, response.getStatus());
        assertEquals(1, handed.size());
        assertEquals("/Submissions/aRequest/d1/v1/file", handed.get(0).target());
        assertEquals("/shared-docs/proposal.md", handed.get(0).markdownPath());
        assertTrue(handed.get(0).succeeded());
        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)),
            "taken, so the record has done its job");
    }

    @Test
    void anOutcomeNobodyTakesKeepsItsRecord() throws Exception
    {
        this.dispatcher.bindHandler(outcome -> false);
        this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, ParseJob.STATUS_ACTIVE,
            ParseJob.PN_PATH, "/shared-docs/proposal.pdf",
            ParseJob.PN_TARGET, "/Submissions/aRequest/d1/v1/file");

        post(GOOD_AUTHORIZATION, SUCCESS_BODY);

        assertEquals(ParseJob.STATUS_COMPLETED, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    @Test
    void aFailureReachesTheHandlerToo() throws Exception
    {
        final List<ParseOutcome> handed = new ArrayList<>();
        this.dispatcher.bindHandler(outcome -> {
            handed.add(outcome);
            return true;
        });
        this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, ParseJob.STATUS_ACTIVE,
            ParseJob.PN_PATH, "/shared-docs/proposal.pdf",
            ParseJob.PN_TARGET, "/Submissions/aRequest/d1/v1/file");

        post(GOOD_AUTHORIZATION, "{\"job_id\": \"" + JOB_ID + "\", \"ok\": false, \"error\": \"No pages\"}");

        assertEquals(1, handed.size());
        assertFalse(handed.get(0).succeeded());
        assertEquals("No pages", handed.get(0).error());
    }

    // The outcome is committed by the time the handlers run, so the delivery succeeded whatever they make of
    // it. Answering 500 would have the daemon deliver a finished parse all over again.
    @Test
    void aHandoverThatBlowsUpDoesNotFailTheDelivery() throws Exception
    {
        final Field outcomes = ParseCallbackServlet.class.getDeclaredField("outcomes");
        outcomes.setAccessible(true);
        outcomes.set(this.servlet, new ParseOutcomeDispatcher()
        {
            @Override
            void settle(final ResourceResolver resolver, final Resource jobNode)
            {
                throw new IllegalStateException("the handover is broken");
            }
        });
        this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, ParseJob.STATUS_ACTIVE,
            ParseJob.PN_PATH, "/shared-docs/proposal.pdf",
            ParseJob.PN_TARGET, "/Submissions/aRequest/d1/v1/file");

        final MockSlingJakartaHttpServletResponse response = post(GOOD_AUTHORIZATION, SUCCESS_BODY);

        assertEquals(200, response.getStatus());
        assertEquals(ParseJob.STATUS_COMPLETED, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    // The conversion's own wall time exists nowhere else: the daemon measures nothing for us and the record is
    // about to be deleted
    @Test
    void measuresTheParseAgainstWhenTheDispatchStarted() throws Exception
    {
        final Calendar started = Calendar.getInstance();
        started.add(Calendar.MINUTE, -3);
        this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, ParseJob.STATUS_ACTIVE,
            ParseJob.PN_PATH, "/shared-docs/proposal.pdf",
            ParseJob.PN_STARTED, started);

        final MockSlingJakartaHttpServletResponse response = post(GOOD_AUTHORIZATION, SUCCESS_BODY);

        assertEquals(200, response.getStatus());
        assertEquals(ParseJob.STATUS_COMPLETED, jobProperties().get(ParseJob.PN_STATUS, String.class));
    }

    private void jobNode()
    {
        this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, ParseJob.STATUS_QUEUED,
            ParseJob.PN_PATH, "/shared-docs/proposal.pdf");
    }

    private ValueMap jobProperties()
    {
        return this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)).getValueMap();
    }

    private MockSlingJakartaHttpServletResponse post(final String authorization, final String body) throws Exception
    {
        return call(this.servlet, authorization, body);
    }

    private MockSlingJakartaHttpServletResponse call(final ParseCallbackServlet target, final String authorization,
        final String body) throws Exception
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setMethod("POST");
        if (authorization != null) {
            request.setHeader("Authorization", authorization);
        }
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        target.service(request, response);
        return response;
    }

    private JsonObject parse(final MockSlingJakartaHttpServletResponse response)
    {
        return Json.createReader(new StringReader(response.getOutputAsString())).readObject();
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

    /**
     * A resolver whose commits fail, standing in for a full or failing repository.
     *
     * @return a resolver that cannot commit
     */
    private ResourceResolver failingCommits()
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public void commit() throws PersistenceException
            {
                throw new PersistenceException("Disk full");
            }
        };
    }
}
