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
package io.uhndata.iap.extraction.internal;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer.JobResult;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExtractAnswersJobConsumer}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ExtractAnswersJobConsumerTest
{
    private static final String JOB_ID = "job-1";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final WorkflowEngine engine = Mockito.mock(WorkflowEngine.class);

    private final Job job = Mockito.mock(Job.class);

    private final ExtractAnswersJobConsumer consumer = new ExtractAnswersJobConsumer();

    private SubmissionTree tree;

    private Resource submission;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        inject("resolverFactory", new TestResolverFactory(this.context.resourceResolver()));
        inject("engine", this.engine);
        inject("runs", new ReadingRuns());
        Mockito.when(this.job.getId()).thenReturn(JOB_ID);
    }

    private void inject(final String name, final Object value) throws Exception
    {
        final Field field = ExtractAnswersJobConsumer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.consumer, value);
    }

    private void setJobSubmission(final String path)
    {
        Mockito.when(this.job.getProperty(ExtractAnswersJobConsumer.SUBMISSION, String.class)).thenReturn(path);
    }

    @Test
    void firesTheEventOnTheSubmission() throws Exception
    {
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        final ArgumentCaptor<Resource> target = ArgumentCaptor.forClass(Resource.class);
        final ArgumentCaptor<WorkflowEvent> event = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine).receiveEvent(target.capture(), event.capture());
        assertEquals(this.submission.getPath(), target.getValue().getPath());
        assertEquals("extractAnswers", event.getValue().getName());
        assertTrue(event.getValue().getPayload().isEmpty());
        // This job's own id, so a racing job's write differs and one of the two commits is refused
        assertEquals(JOB_ID, this.context.resourceResolver().getResource(this.submission.getPath()).getValueMap()
            .get(ExtractionStatus.READING_CLAIMED_BY, String.class));
    }

    // Sling runs an unfinished job again after a restart, with the same id
    @Test
    void readsWhenTheClaimIsAlreadyItsOwn() throws Exception
    {
        claim(JOB_ID);
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        Mockito.verify(this.engine).receiveEvent(Mockito.any(), Mockito.any());
    }

    @Test
    void dropsTheJobWhenAnotherJobHoldsTheClaim() throws Exception
    {
        claim("job-2");
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }

    // Queued before the walk asking for it commits, so the old claim may still be there
    @Test
    void waitsForTheRequestToReadAgainToCommit() throws Exception
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.PROPERTY, ExtractionStatus.DONE);
        claim("job-0");
        setJobSubmission(this.submission.getPath());
        Mockito.when(this.job.getProperty(ExtractAnswersJobConsumer.READ_AGAIN, Boolean.class))
            .thenReturn(Boolean.TRUE);

        assertEquals(JobResult.FAILED, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void readsAgainOnceTheRequestIsCommitted() throws Exception
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.PROPERTY, ExtractionStatus.RUNNING);
        setJobSubmission(this.submission.getPath());
        Mockito.when(this.job.getProperty(ExtractAnswersJobConsumer.READ_AGAIN, Boolean.class))
            .thenReturn(Boolean.TRUE);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        Mockito.verify(this.engine).receiveEvent(Mockito.any(), Mockito.any());
    }

    private void claim(final String jobId) throws Exception
    {
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.READING_CLAIMED, Boolean.TRUE);
        properties.put(ExtractionStatus.READING_CLAIMED_BY, jobId);
        this.context.resourceResolver().commit();
    }

    // Fired anyway, the reading would start nothing and the submission would show `running` for good
    @Test
    void givesUpWhenTheSchemaNamesNoReadingWorkflow() throws Exception
    {
        getSchemaVersionProperties().remove(ParseDocumentsHandler.READING_WORKFLOW);
        this.context.resourceResolver().commit();
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertGaveUpBeforeReading();
    }

    @Test
    void givesUpWhenTheReadingWorkflowIsNotAWorkflow() throws Exception
    {
        SubmissionTree.reference(this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH),
            ParseDocumentsHandler.READING_WORKFLOW,
            this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH));
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertGaveUpBeforeReading();
    }

    @Test
    void givesUpWhenTheReadingWorkflowLeadsNowhere() throws Exception
    {
        getSchemaVersionProperties().put(ParseDocumentsHandler.READING_WORKFLOW,
            "1e17e5b1-0000-0000-0000-000000000000");
        this.context.resourceResolver().commit();
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertGaveUpBeforeReading();
    }

    private ModifiableValueMap getSchemaVersionProperties()
    {
        return this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH)
            .adaptTo(ModifiableValueMap.class);
    }

    private void assertGaveUpBeforeReading()
    {
        Mockito.verifyNoInteractions(this.engine);
        final ValueMap properties = this.context.resourceResolver().getResource(this.submission.getPath())
            .getValueMap();
        assertEquals(ExtractionStatus.FAILED, properties.get(ExtractionStatus.PROPERTY, String.class));
        assertEquals(ExtractAnswersJobConsumer.NOTHING_READS, properties.get(ExtractionStatus.MESSAGE, String.class));
        assertNull(properties.get(ExtractionStatus.READING_CLAIMED, Boolean.class));
    }

    @Test
    void dropsAJobThatNamesNoSubmission()
    {
        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void dropsAJobForASubmissionThatIsGone()
    {
        setJobSubmission("/Submissions/aa/bb/cc/gone");

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void doesNotRetryWhenTheEngineRefuses() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenThrow(new NotAuthorizedException("not yours"));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));
    }

    // The engine reverts the whole walk, so the `running` the parse step committed is still standing and this
    // job still holds the claim. Left alone that is a submission nothing can ever read again.
    @Test
    void saysSoAndLetsTheClaimGoWhenTheReadingFails() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenThrow(new NotAuthorizedException("not yours"));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        final ValueMap properties = this.submission.getValueMap();
        assertEquals(ExtractionStatus.FAILED, properties.get(ExtractionStatus.PROPERTY, String.class));
        assertNotNull(properties.get(ExtractionStatus.MESSAGE, String.class), "and the person is told why");
        assertNull(properties.get(ExtractionStatus.READING_CLAIMED, Boolean.class),
            "so a later parse, or somebody asking again, can take the reading");
        assertNull(properties.get(ExtractionStatus.READING_CLAIMED_BY, String.class));
    }

    // A malformed definition throws rather than returning. It must not go to Sling's retry: the claim is already
    // let go, so a retry would claim the reading again and pay the model twice
    @Test
    void saysSoWhenTheReadingFailsWithSomethingUnchecked() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenThrow(new IllegalStateException("a definition nobody can walk"));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ExtractionStatus.FAILED,
            this.submission.getValueMap().get(ExtractionStatus.PROPERTY, String.class));
    }

    // A stop that lands once the last model call is over has nothing to stop, and must not stay on the pooled
    // thread for the next job to trip over
    @Test
    void clearsAStopThatCameAfterTheLastModelCall() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        });

        try {
            assertEquals(JobResult.OK, this.consumer.process(this.job));
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void recordsAStopAndDoesNotRetry() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenThrow(new ReadingRuns.Stopped());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ExtractionStatus.STOPPED,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStopThatCannotBeRecordedIsStillNotRetried() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        final ResourceResolverFactory real = new TestResolverFactory(this.context.resourceResolver());
        final ResourceResolverFactory flaky = Mockito.mock(ResourceResolverFactory.class);
        final AtomicInteger opened = new AtomicInteger();
        Mockito.when(flaky.getServiceResourceResolver(Mockito.anyMap())).thenAnswer(invocation -> {
            if (opened.incrementAndGet() > 1) {
                throw new LoginException("no");
            }
            return real.getServiceResourceResolver(invocation.getArgument(0));
        });
        inject("resolverFactory", flaky);
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenThrow(new ReadingRuns.Stopped());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));
    }

    @Test
    void aReadingInterruptedMidCallIsAStop() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new NotAuthorizedException("closed");
        });

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ExtractionStatus.STOPPED,
            this.submission.getValueMap().get(ExtractionStatus.MESSAGE, String.class));
        Thread.interrupted();
    }

    @Test
    void dropsTheJobWithoutTheServiceUser() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        inject("resolverFactory", new TestResolverFactory(null));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }

    // Every parse that lands queues a job, so the first ones to arrive are for a submission that is not ready
    // to be read yet. Retried rather than dropped: the job is queued from inside the walk that records the
    // parse, so one starting quickly enough sees its own parse as unfinished, and a drop there would leave the
    // submission at `running` for good.
    @Test
    void looksAgainWhileAParseIsStillGoing()
    {
        this.tree.createFile("completed");
        this.tree.createFile("queued");
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.FAILED, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }

    // The document is read once, however many parses landed at the same moment
    @Test
    void readsASubmissionOnlyOnce() throws Exception
    {
        this.tree.createFile("completed");
        setJobSubmission(this.submission.getPath());

        final Job other = Mockito.mock(Job.class);
        Mockito.when(other.getProperty(ExtractAnswersJobConsumer.SUBMISSION, String.class))
            .thenReturn(this.submission.getPath());
        Mockito.when(other.getId()).thenReturn("job-2");

        assertEquals(JobResult.OK, this.consumer.process(this.job));
        assertEquals(JobResult.CANCEL, this.consumer.process(other));

        Mockito.verify(this.engine, Mockito.times(1)).receiveEvent(Mockito.any(), Mockito.any());
    }

    // New documents are a new reading to be had, so the claim the last one left is taken back down
    @Test
    void readsASubmissionAgainOnceItsClaimHasBeenTakenDown() throws Exception
    {
        this.tree.createFile("completed");
        setJobSubmission(this.submission.getPath());
        assertEquals(JobResult.OK, this.consumer.process(this.job));

        this.submission.adaptTo(ModifiableValueMap.class).remove("extractionReadingClaimed");

        assertEquals(JobResult.OK, this.consumer.process(this.job));
        Mockito.verify(this.engine, Mockito.times(2)).receiveEvent(Mockito.any(), Mockito.any());
    }

    @Test
    void dropsTheJobWhenTheClaimCannotBeWritten() throws Exception
    {
        this.tree.createFile("completed");
        setJobSubmission(this.submission.getPath());
        inject("resolverFactory", new TestResolverFactory(createReadOnlyResolver()));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }

    // Two jobs both found everything settled and both wrote the claim; the repository refuses the second
    @Test
    void dropsTheJobWhoseClaimTheRepositoryRefuses() throws Exception
    {
        this.tree.createFile("completed");
        setJobSubmission(this.submission.getPath());
        inject("resolverFactory", new TestResolverFactory(new ResourceResolverWrapper(
            this.context.resourceResolver())
        {
            @Override
            public void commit() throws PersistenceException
            {
                throw new PersistenceException("somebody else got there first");
            }

            @Override
            public void revert()
            {
                // The mock repository has no session to roll back
            }
        }));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }

    // Giving up is the last thing that can help this submission, so when it cannot be written the job still ends
    // rather than taking the failure somewhere nobody handles it
    @Test
    void endsTheJobEvenWhenGivingUpCannotBeRecorded() throws Exception
    {
        this.tree.createFile("completed");
        setJobSubmission(this.submission.getPath());
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenThrow(new NotAuthorizedException("not yours"));
        inject("resolverFactory", new TestResolverFactory(createClaimableThenReadOnlyResolver()));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));
    }

    @Test
    void endsTheJobWhenTheGivingUpCommitIsRefused() throws Exception
    {
        this.tree.createFile("completed");
        setJobSubmission(this.submission.getPath());
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any()))
            .thenThrow(new NotAuthorizedException("not yours"));
        inject("resolverFactory", new TestResolverFactory(new ResourceResolverWrapper(
            this.context.resourceResolver())
        {
            private boolean claimed;

            @Override
            public void commit() throws PersistenceException
            {
                // The claim goes through; the giving-up that follows it does not
                if (this.claimed) {
                    throw new PersistenceException("the repository has moved on");
                }
                this.claimed = true;
                super.commit();
            }

            @Override
            public void revert()
            {
                // The mock repository has no session to roll back
            }
        }));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));
    }

    /**
     * A session that lets the claim be written and then refuses every write after it, so that giving up meets a
     * submission it cannot record anything on.
     *
     * @return the session
     */
    private ResourceResolver createClaimableThenReadOnlyResolver()
    {
        final ResourceResolver real = this.context.resourceResolver();
        final int[] writes = { 0 };
        return new ResourceResolverWrapper(real)
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource found = real.getResource(path);
                // The first write is the claim; giving up writes through the same resource again
                return found == null ? null : new ResourceWrapper(found)
                {
                    @Override
                    public <T> T adaptTo(final Class<T> type)
                    {
                        if (type == ModifiableValueMap.class && ++writes[0] > 1) {
                            return null;
                        }
                        return super.adaptTo(type);
                    }
                };
            }
        };
    }

    /**
     * A session that hands back resources nothing can be written on, which a mock repository does not do by
     * itself.
     *
     * @return the session
     */
    private ResourceResolver createReadOnlyResolver()
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource found = super.getResource(path);
                return found == null ? null : new ResourceWrapper(found)
                {
                    @Override
                    public <T> T adaptTo(final Class<T> type)
                    {
                        return type == ModifiableValueMap.class ? null : super.adaptTo(type);
                    }
                };
            }
        };
    }
}
