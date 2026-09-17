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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParseJobService}: where files are staged, and how a job is recorded and queued.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ParseJobServiceTest
{
    private static final String DOCUMENT = "/shared-docs/proposal.pdf";

    private static final String TARGET = "/Submissions/ab/cd/ef/aRequest/d1/v1/file";

    private final SlingContext context = new SlingContext();

    private final JobManager jobManager = Mockito.mock(JobManager.class);

    @TempDir
    private Path volume;

    private ParseJobService service;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.create().resource(ParseJob.JOBS_PATH);
        this.service = serviceWithEnvironment(null);
        this.service.activate(Map.of(ParseJobService.SHARED_DOCS_PROPERTY, this.volume.toString()));
    }

    @Test
    void stagesAFileInAFolderOfItsOwn() throws IOException
    {
        final String staged = this.service.stage("proposal.pdf", bytes("%PDF"));

        final Path path = Path.of(staged);
        assertEquals("proposal.pdf", path.getFileName().toString());
        assertEquals(this.volume, path.getParent().getParent(), "one folder down from the volume");
        assertEquals("%PDF", Files.readString(path, StandardCharsets.UTF_8));
    }

    @Test
    void twoFilesWithTheSameNameDoNotCollide() throws IOException
    {
        final String first = this.service.stage("proposal.pdf", bytes("one"));
        final String second = this.service.stage("proposal.pdf", bytes("two"));

        assertTrue(!first.equals(second));
        assertEquals("one", Files.readString(Path.of(first), StandardCharsets.UTF_8));
        assertEquals("two", Files.readString(Path.of(second), StandardCharsets.UTF_8));
    }

    @Test
    void makesAFileNameSafeButKeepsItsExtension()
    {
        assertEquals("scan__page__1__2.pdf", ParseJobService.usableName("scan: page [1]/2.pdf"));
        assertEquals("_.._.._etc_passwd", ParseJobService.usableName("/../../etc/passwd"),
            "nothing left that could name another directory");
        assertEquals("document", ParseJobService.usableName(".."));
        assertEquals("document", ParseJobService.usableName("   "));
        assertEquals("document", ParseJobService.usableName(null));
    }

    @Test
    void queuesAJobForAFile() throws IOException
    {
        Mockito.when(this.jobManager.addJob(Mockito.eq(ParseJob.TOPIC), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));

        final String jobId = this.service.queue(DOCUMENT, true, TARGET);

        final ValueMap job = jobProperties(jobId);
        assertEquals(jobId, job.get(ParseJob.PN_JOB_ID, String.class));
        assertEquals(ParseJob.STATUS_QUEUED, job.get(ParseJob.PN_STATUS, String.class));
        assertEquals(DOCUMENT, job.get(ParseJob.PN_PATH, String.class));
        assertEquals(Boolean.TRUE, job.get(ParseJob.PN_CHUNK, Boolean.class));
        assertEquals(TARGET, job.get(ParseJob.PN_TARGET, String.class));
        assertNotNull(job.get(ParseJob.PN_CREATED));
        Mockito.verify(this.jobManager).addJob(ParseJob.TOPIC, Map.of(ParseJob.PN_JOB_ID, jobId));
    }

    @Test
    void recordsNoTargetForAParseNobodyIsWaitingOn() throws IOException
    {
        Mockito.when(this.jobManager.addJob(Mockito.eq(ParseJob.TOPIC), Mockito.anyMap()))
            .thenReturn(Mockito.mock(Job.class));

        assertNull(jobProperties(this.service.queue(DOCUMENT, false, null)).get(ParseJob.PN_TARGET, String.class));
        assertNull(jobProperties(this.service.queue(DOCUMENT, false, "  ")).get(ParseJob.PN_TARGET, String.class));
    }

    @Test
    void refusesWhenTheStorageIsNotInitialized() throws PersistenceException
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        resolver.delete(resolver.getResource(ParseJob.JOBS_PATH));

        final IOException failure = assertThrows(IOException.class, () -> this.service.queue(DOCUMENT, true, null));

        assertTrue(failure.getMessage().contains("not initialized"));
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void refusesWithoutTheServiceUser() throws Exception
    {
        inject("resolverFactory", new TestResolverFactory(null));

        final IOException failure = assertThrows(IOException.class, () -> this.service.queue(DOCUMENT, true, null));

        assertTrue(failure.getMessage().contains("not accessible"));
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void refusesWhenTheJobCannotBeRecorded() throws Exception
    {
        inject("resolverFactory", new TestResolverFactory(failingCommits(0)));

        final IOException failure = assertThrows(IOException.class, () -> this.service.queue(DOCUMENT, true, null));

        assertTrue(failure.getMessage().contains("could not be recorded"));
        Mockito.verifyNoInteractions(this.jobManager);
    }

    @Test
    void marksTheJobFailedWhenItCannotBeQueued()
    {
        // The unstubbed JobManager refuses the job by answering null
        final IOException failure = assertThrows(IOException.class, () -> this.service.queue(DOCUMENT, true, null));

        assertTrue(failure.getMessage().contains("could not be queued"));
        final ValueMap job = this.context.resourceResolver().getResource(ParseJob.JOBS_PATH)
            .getChildren().iterator().next().getValueMap();
        assertEquals(ParseJob.STATUS_FAILED, job.get(ParseJob.PN_STATUS, String.class));
        assertNotNull(job.get(ParseJob.PN_ERROR, String.class));
    }

    @Test
    void aQueueRefusalSurvivesACommitFailure() throws Exception
    {
        // The job node is committed, the unstubbed JobManager refuses the job, and marking the failure fails too
        inject("resolverFactory", new TestResolverFactory(failingCommits(1)));

        final IOException failure = assertThrows(IOException.class, () -> this.service.queue(DOCUMENT, true, null));

        assertTrue(failure.getMessage().contains("could not be queued"));
    }

    @Test
    void aQueueRefusalOnAnUnmodifiableNodeStillFails() throws Exception
    {
        inject("resolverFactory", new TestResolverFactory(unmodifiableCreations()));

        final IOException failure = assertThrows(IOException.class, () -> this.service.queue(DOCUMENT, true, null));

        assertTrue(failure.getMessage().contains("could not be queued"));
    }

    @Test
    void readsTheVolumeFromTheEnvironmentWhenNothingIsConfigured() throws Exception
    {
        final ParseJobService fromEnvironment = serviceWithEnvironment("/mnt/docs");
        fromEnvironment.activate(Map.of());

        assertEquals(Path.of("/mnt/docs"), fromEnvironment.getSharedDocs());
    }

    @Test
    void fallsBackToTheDefaultVolume() throws Exception
    {
        final ParseJobService bare = serviceWithEnvironment("  ");
        bare.activate(Map.of(ParseJobService.SHARED_DOCS_PROPERTY, ""));

        assertEquals(Path.of(ParseJobService.DEFAULT_SHARED_DOCS), bare.getSharedDocs());
    }

    @Test
    void theRealEnvironmentLookupIsHarmless()
    {
        final ParseJobService real = new ParseJobService();
        real.activate(Map.of());

        assertNotNull(real.getSharedDocs());
    }

    private ParseJobService serviceWithEnvironment(final String volumeVariable) throws Exception
    {
        final ParseJobService built = new ParseJobService()
        {
            @Override
            protected String environment(final String name)
            {
                return ParseJobService.SHARED_DOCS_VARIABLE.equals(name) ? volumeVariable : null;
            }
        };
        inject(built, "resolverFactory", new TestResolverFactory(this.context.resourceResolver()));
        inject(built, "jobManager", this.jobManager);
        return built;
    }

    private void inject(final String name, final Object value) throws Exception
    {
        inject(this.service, name, value);
    }

    private static void inject(final ParseJobService target, final String name, final Object value)
        throws Exception
    {
        final Field reference = ParseJobService.class.getDeclaredField(name);
        reference.setAccessible(true);
        reference.set(target, value);
    }

    private static ByteArrayInputStream bytes(final String text)
    {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private ValueMap jobProperties(final String jobId)
    {
        return this.context.resourceResolver().getResource(ParseJob.nodePath(jobId)).getValueMap();
    }

    /** A resolver whose commits break after a while, standing in for a full or failing repository. */
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

    /** A resolver whose created resources refuse to be adapted for editing. */
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
}
