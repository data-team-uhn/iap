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
import java.util.Map;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParseServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ParseServletTest
{
    private final SlingContext context = new SlingContext();

    private final JobManager jobManager = Mockito.mock(JobManager.class);

    private ParseServlet servlet;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.create().resource(ParseJob.JOBS_PATH);
        this.servlet = new ParseServlet();
        inject("resolverFactory", new TestResolverFactory(this.context.resourceResolver()));
        inject("jobManager", this.jobManager);
    }

    @Test
    void postQueuesAJob() throws Exception
    {
        Mockito.when(this.jobManager.addJob(Mockito.eq(ParseJob.TOPIC), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));

        final MockSlingJakartaHttpServletResponse response =
            post(Map.of(ParseJob.PN_PATH, "/shared-docs/proposal.pdf"));

        assertEquals(202, response.getStatus());
        final JsonObject json = parse(response);
        final String jobId = json.getString("job_id");
        assertEquals(ParseJob.STATUS_QUEUED, json.getString("status"));
        // Points at the poll endpoint, built from the constant servlet path, never from the request
        assertEquals(ParseServlet.PATH + "?job_id=" + jobId, response.getHeader("Location"));

        final ValueMap job = jobProperties(jobId);
        assertEquals(ParseJob.STATUS_QUEUED, job.get(ParseJob.PN_STATUS, String.class));
        assertEquals("/shared-docs/proposal.pdf", job.get(ParseJob.PN_PATH, String.class));
        assertEquals(Boolean.TRUE, job.get(ParseJob.PN_CHUNK, Boolean.class));
        assertNotNull(job.get(ParseJob.PN_CREATED));
        Mockito.verify(this.jobManager).addJob(ParseJob.TOPIC, Map.of(ParseJob.PN_JOB_ID, jobId));
    }

    @Test
    void postHonoursChunkFalse() throws Exception
    {
        Mockito.when(this.jobManager.addJob(Mockito.eq(ParseJob.TOPIC), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));

        final MockSlingJakartaHttpServletResponse response =
            post(Map.of(ParseJob.PN_PATH, "/shared-docs/proposal.pdf", ParseJob.PN_CHUNK, "false"));

        final String jobId = parse(response).getString("job_id");
        assertEquals(Boolean.FALSE, jobProperties(jobId).get(ParseJob.PN_CHUNK, Boolean.class));
    }

    @Test
    void postWithoutPathIsRejected() throws Exception
    {
        final MockSlingJakartaHttpServletResponse response = post(Map.of());

        assertEquals(400, response.getStatus());
        assertTrue(parse(response).getString("error").contains("path"));
        assertFalse(this.context.resourceResolver().getResource(ParseJob.JOBS_PATH).hasChildren());
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void postThatCannotBeQueuedMarksTheJobFailed() throws Exception
    {
        // The unstubbed JobManager refuses the job by answering null

        final MockSlingJakartaHttpServletResponse response =
            post(Map.of(ParseJob.PN_PATH, "/shared-docs/proposal.pdf"));

        assertEquals(500, response.getStatus());
        final ValueMap job = this.context.resourceResolver().getResource(ParseJob.JOBS_PATH)
            .getChildren().iterator().next().getValueMap();
        assertEquals(ParseJob.STATUS_FAILED, job.get(ParseJob.PN_STATUS, String.class));
        assertNotNull(job.get(ParseJob.PN_ERROR, String.class));
    }

    @Test
    void getWithoutJobIdIsRejected() throws Exception
    {
        final MockSlingJakartaHttpServletResponse response = get(Map.of());

        assertEquals(400, response.getStatus());
    }

    @Test
    void getWithMalformedJobIdIsRejected() throws Exception
    {
        final MockSlingJakartaHttpServletResponse response = get(Map.of("job_id", "../../oops"));

        assertEquals(400, response.getStatus());
        assertTrue(parse(response).getString("error").contains("UUID"));
    }

    @Test
    void getUnknownJobIsNotFound() throws Exception
    {
        final MockSlingJakartaHttpServletResponse response =
            get(Map.of("job_id", UUID.randomUUID().toString()));

        assertEquals(404, response.getStatus());
    }

    @Test
    void getReportsAQueuedJob() throws Exception
    {
        final String jobId = job(ParseJob.STATUS_QUEUED);

        final JsonObject json = parse(get(Map.of("job_id", jobId)));

        assertEquals(jobId, json.getString("job_id"));
        assertEquals(ParseJob.STATUS_QUEUED, json.getString("status"));
        assertFalse(json.containsKey("outputs"));
        assertFalse(json.containsKey("error"));
    }

    @Test
    void getReportsACompletedJobWithItsOutputs() throws Exception
    {
        final String jobId = job(ParseJob.STATUS_COMPLETED,
            ParseJob.PN_OUTPUTS, new String[] { "/shared-docs/proposal.md", "/shared-docs/Chunks" });

        final JsonObject json = parse(get(Map.of("job_id", jobId)));

        assertEquals(ParseJob.STATUS_COMPLETED, json.getString("status"));
        assertEquals(2, json.getJsonArray("outputs").size());
        assertEquals("/shared-docs/proposal.md", json.getJsonArray("outputs").getString(0));
        assertEquals("/shared-docs/Chunks", json.getJsonArray("outputs").getString(1));
    }

    @Test
    void getReportsAFailedJobWithItsError() throws Exception
    {
        final String jobId = job(ParseJob.STATUS_FAILED, ParseJob.PN_ERROR, "The daemon answered HTTP 500: boom");

        final JsonObject json = parse(get(Map.of("job_id", jobId)));

        assertEquals(ParseJob.STATUS_FAILED, json.getString("status"));
        assertEquals("The daemon answered HTTP 500: boom", json.getString("error"));
        assertNull(json.getJsonArray("outputs"));
    }

    @Test
    void postWithoutInitializedStorageFails() throws Exception
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        resolver.delete(resolver.getResource(ParseJob.JOBS_PATH));

        final MockSlingJakartaHttpServletResponse response =
            post(Map.of(ParseJob.PN_PATH, "/shared-docs/proposal.pdf"));

        assertEquals(500, response.getStatus());
        assertTrue(parse(response).getString("error").contains("not initialized"));
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void everythingFailsWithoutTheServiceUser() throws Exception
    {
        inject("resolverFactory", new TestResolverFactory(null));

        assertEquals(500, post(Map.of(ParseJob.PN_PATH, "/shared-docs/proposal.pdf")).getStatus());
        assertEquals(500, get(Map.of("job_id", UUID.randomUUID().toString())).getStatus());
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void unrecordablePostFails() throws Exception
    {
        inject("resolverFactory", new TestResolverFactory(failingCommits(0)));

        final MockSlingJakartaHttpServletResponse response =
            post(Map.of(ParseJob.PN_PATH, "/shared-docs/proposal.pdf"));

        assertEquals(500, response.getStatus());
        assertTrue(parse(response).getString("error").contains("could not be recorded"));
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void queueRefusalSurvivesACommitFailure() throws Exception
    {
        // The job node is committed, the unstubbed JobManager refuses the job, and marking the failure fails too
        inject("resolverFactory", new TestResolverFactory(failingCommits(1)));

        final MockSlingJakartaHttpServletResponse response =
            post(Map.of(ParseJob.PN_PATH, "/shared-docs/proposal.pdf"));

        assertEquals(500, response.getStatus());
        assertTrue(parse(response).getString("error").contains("could not be queued"));
    }

    @Test
    void queueRefusalOnAnUnmodifiableNodeStillFails() throws Exception
    {
        inject("resolverFactory", new TestResolverFactory(unmodifiableCreations()));

        final MockSlingJakartaHttpServletResponse response =
            post(Map.of(ParseJob.PN_PATH, "/shared-docs/proposal.pdf"));

        assertEquals(500, response.getStatus());
        assertTrue(parse(response).getString("error").contains("could not be queued"));
    }

    /**
     * A resolver whose commits break after a while, standing in for a full or failing repository.
     *
     * @param allowed how many commits succeed before they start failing
     * @return a resolver that eventually cannot commit
     */
    private ResourceResolver failingCommits(final int allowed)
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            private int commits;

            @Override
            public void commit() throws PersistenceException
            {
                if (this.commits++ >= allowed) {
                    throw new PersistenceException("Disk full");
                }
                super.commit();
            }
        };
    }

    /**
     * A resolver whose created resources refuse to be adapted for editing.
     *
     * @return a resolver handing out read-only creations
     */
    private ResourceResolver unmodifiableCreations()
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource create(final Resource parent, final String name, final Map<String, Object> properties)
                throws PersistenceException
            {
                final Resource spy = Mockito.spy(super.create(parent, name, properties));
                Mockito.doReturn(null).when(spy).adaptTo(ModifiableValueMap.class);
                return spy;
            }
        };
    }

    private void inject(final String name, final Object value) throws Exception
    {
        final Field reference = ParseServlet.class.getDeclaredField(name);
        reference.setAccessible(true);
        reference.set(this.servlet, value);
    }

    /**
     * Create a job node directly in the repository, as the servlet and the consumer would have left it.
     *
     * @param status the status to record
     * @param extraProperties additional properties, alternating name and value
     * @return the identifier of the created job
     */
    private String job(final String status, final Object... extraProperties)
    {
        final String jobId = UUID.randomUUID().toString();
        final Object[] properties = new Object[extraProperties.length + 4];
        properties[0] = ParseJob.PN_JOB_ID;
        properties[1] = jobId;
        properties[2] = ParseJob.PN_STATUS;
        properties[3] = status;
        System.arraycopy(extraProperties, 0, properties, 4, extraProperties.length);
        this.context.create().resource(ParseJob.nodePath(jobId), properties);
        return jobId;
    }

    private ValueMap jobProperties(final String jobId)
    {
        return this.context.resourceResolver().getResource(ParseJob.nodePath(jobId)).getValueMap();
    }

    private MockSlingJakartaHttpServletResponse post(final Map<String, Object> parameters) throws Exception
    {
        return call("POST", parameters);
    }

    private MockSlingJakartaHttpServletResponse get(final Map<String, Object> parameters) throws Exception
    {
        return call("GET", parameters);
    }

    private MockSlingJakartaHttpServletResponse call(final String method, final Map<String, Object> parameters)
        throws Exception
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        request.setMethod(method);
        request.setParameterMap(parameters);
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        this.servlet.service(request, response);
        return response;
    }

    private JsonObject parse(final MockSlingJakartaHttpServletResponse response)
    {
        return Json.createReader(new StringReader(response.getOutputAsString())).readObject();
    }
}
