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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
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
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExtractAnswersJobConsumer}: when a job reads a submission, waits, or gives up.
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

    private final ReadingJobs jobs = new ReadingJobs();

    /** What the stand-in engine does for each event, by name. */
    private final Map<String, Answer<Object>> steps = new HashMap<>();

    private SubmissionTree tree;

    private Resource submission;

    @BeforeEach
    void setUp() throws Exception
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        inject(this.jobs, "resolverFactory", new TestResolverFactory(this.context.resourceResolver()));
        inject(this.jobs, "engine", this.engine);
        inject(this.jobs, "runs", new ReadingRuns());
        inject(this.consumer, "jobs", this.jobs);
        inject(this.consumer, "engine", this.engine);
        Mockito.when(this.job.getId()).thenReturn(JOB_ID);
        this.steps.put(ReadingJobs.READING_STARTED_EVENT,
            call -> runStep(new ClaimReadingHandler(), call));
        this.steps.put(ReadingJobs.READING_FAILED_EVENT,
            call -> runStep(new GiveUpReadingHandler(), call));
        this.steps.put(ExtractAnswersJobConsumer.EXTRACT_ANSWERS_EVENT, call -> null);
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any())).thenAnswer(
            call -> this.steps.get(call.<WorkflowEvent>getArgument(1).getName()).answer(call));
    }

    private static void inject(final Object component, final String name, final Object value) throws Exception
    {
        final Field field = component.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(component, value);
    }

    /**
     * Runs a status step as the engine would: through its own session, committed when the step succeeds.
     */
    private Object runStep(final ServiceTaskHandler handler, final InvocationOnMock call) throws Exception
    {
        final Resource target =
            this.context.resourceResolver().getResource(call.<Resource>getArgument(0).getPath());
        handler.execute(TaskContexts.of(target, call.<WorkflowEvent>getArgument(1).getPayload(), new HashMap<>()));
        this.context.resourceResolver().commit();
        return null;
    }

    private void setReading(final Answer<Object> reading)
    {
        this.steps.put(ExtractAnswersJobConsumer.EXTRACT_ANSWERS_EVENT, reading);
    }

    private void setJobSubmission(final String path)
    {
        Mockito.when(this.job.getProperty(ReadingJobs.SUBMISSION_PROPERTY,
            String.class)).thenReturn(path);
    }

    private List<String> getFiredEvents() throws Exception
    {
        final ArgumentCaptor<WorkflowEvent> events = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine, Mockito.atLeast(0)).receiveEvent(Mockito.any(), events.capture());
        return events.getAllValues().stream().map(WorkflowEvent::getName).toList();
    }

    private long countReadings() throws Exception
    {
        return getFiredEvents().stream().filter(ExtractAnswersJobConsumer.EXTRACT_ANSWERS_EVENT::equals).count();
    }

    private ValueMap getProperties()
    {
        return this.context.resourceResolver().getResource(this.submission.getPath()).getValueMap();
    }

    @Test
    void claimsTheReadingThenFiresTheEventOnTheSubmission() throws Exception
    {
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        final ArgumentCaptor<Resource> target = ArgumentCaptor.forClass(Resource.class);
        final ArgumentCaptor<WorkflowEvent> event = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine, Mockito.times(2)).receiveEvent(target.capture(), event.capture());
        assertEquals(List.of("readingStarted", "extractAnswers"),
            event.getAllValues().stream().map(WorkflowEvent::getName).toList());
        assertEquals(this.submission.getPath(), target.getAllValues().get(1).getPath());
        assertEquals(JOB_ID, event.getAllValues().get(0).getPayload().get(ClaimReadingHandler.JOB_KEY));
        assertTrue(event.getAllValues().get(1).getPayload().isEmpty());
        assertEquals(JOB_ID, getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    // Sling runs an unfinished job again after a restart, with the same id
    @Test
    void readsWhenTheClaimIsAlreadyItsOwn() throws Exception
    {
        claim(JOB_ID);
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(1, countReadings());
    }

    @Test
    void dropsTheJobWhenAnotherJobHoldsTheClaim() throws Exception
    {
        claim("job-2");
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted"), getFiredEvents());
        assertEquals("job-2", getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    // Two jobs both found the claim unset and both claimed; the engine refuses the second commit
    @Test
    void dropsTheJobWhoseClaimLostTheRace() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        this.steps.put(ReadingJobs.READING_STARTED_EVENT, call -> {
            throw new InvalidStateException("Somebody else changed this at the same time");
        });

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(List.of("readingStarted"), getFiredEvents(), "and it does not give up the winner's reading");
    }

    // `running` was committed before the job: left as it is, the spinner would never stop
    @Test
    void givesUpWhenTheEngineRefusesTheClaimForAnotherReason() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        this.steps.put(ReadingJobs.READING_STARTED_EVENT, call -> {
            throw new NotAuthorizedException("not the extraction user");
        });

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(0, countReadings());
        assertEquals(ExtractionStatus.STATUS_FAILED,
            getProperties().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals(ReadingJobs.COULD_NOT_READ_MESSAGE,
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    // The job can start before the walk that asked for it commits, so the old claim may still be there
    @Test
    void waitsForTheRequestToReadAgainToCommit() throws Exception
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_DONE);
        claim("job-0");
        setJobSubmission(this.submission.getPath());
        Mockito.when(this.job.getProperty(ReadingJobs.READ_AGAIN_PROPERTY, Boolean.class))
            .thenReturn(Boolean.TRUE);

        assertEquals(JobResult.FAILED, this.consumer.process(this.job));

        assertTrue(getFiredEvents().isEmpty());
    }

    @Test
    void readsAgainOnceTheRequestIsCommitted() throws Exception
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);
        setJobSubmission(this.submission.getPath());
        Mockito.when(this.job.getProperty(ReadingJobs.READ_AGAIN_PROPERTY, Boolean.class))
            .thenReturn(Boolean.TRUE);

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        assertEquals(1, countReadings());
    }

    private void claim(final String jobId) throws Exception
    {
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.TRUE);
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, jobId);
        this.context.resourceResolver().commit();
    }

    // If the event were fired anyway, the reading would start nothing and the submission would show `running`
    // forever
    @Test
    void givesUpWhenTheSchemaNamesNoReadingWorkflow() throws Exception
    {
        getSchemaVersionProperties().remove(SubmissionFiles.READING_WORKFLOW_PROPERTY);
        this.context.resourceResolver().commit();
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertGaveUpBeforeReading();
    }

    @Test
    void givesUpWhenTheReadingWorkflowIsNotAWorkflow() throws Exception
    {
        SubmissionTree.reference(this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH),
            SubmissionFiles.READING_WORKFLOW_PROPERTY,
            this.context.resourceResolver().getResource(SubmissionTree.FORM_PATH));
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertGaveUpBeforeReading();
    }

    @Test
    void givesUpWhenTheReadingWorkflowLeadsNowhere() throws Exception
    {
        getSchemaVersionProperties().put(SubmissionFiles.READING_WORKFLOW_PROPERTY,
            "1e17e5b1-0000-0000-0000-000000000000");
        this.context.resourceResolver().commit();
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertGaveUpBeforeReading();
    }

    // A version that is not active takes no new instances, so starting it would fail later with a vaguer message
    @Test
    void givesUpWhenTheReadingWorkflowIsNotActive() throws Exception
    {
        this.context.resourceResolver().getResource(SubmissionTree.READING_PATH).adaptTo(ModifiableValueMap.class)
            .put("tags", new String[] { "retired" });
        this.context.resourceResolver().commit();
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertGaveUpBeforeReading();
    }

    // The job's session cannot read the schema version. Without giving up, the submission would stay `running`.
    @Test
    void givesUpWhenTheSchemaVersionCannotBeRead() throws Exception
    {
        this.context.resourceResolver().getResource(this.submission.getPath()).adaptTo(ModifiableValueMap.class)
            .put("schemaVersion", "5c4e3a1b-0000-0000-0000-000000000000");
        this.context.resourceResolver().commit();
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(List.of("readingFailed"), getFiredEvents());
        assertEquals(ExtractionStatus.STATUS_FAILED,
            getProperties().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals(ReadingJobs.COULD_NOT_READ_MESSAGE,
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    private ModifiableValueMap getSchemaVersionProperties()
    {
        return this.context.resourceResolver().getResource(SubmissionTree.VERSION_PATH)
            .adaptTo(ModifiableValueMap.class);
    }

    private void assertGaveUpBeforeReading() throws Exception
    {
        assertEquals(List.of("readingFailed"), getFiredEvents());
        final ValueMap properties = getProperties();
        assertEquals(ExtractionStatus.STATUS_FAILED, properties.get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals(ExtractAnswersJobConsumer.NO_READING_WORKFLOW_MESSAGE,
            properties.get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
        assertNull(properties.get(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.class));
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

    // The engine undoes the whole walk, so the `running` status and this job's claim are still there. Left
    // alone, nothing could ever read the submission again.
    @Test
    void saysSoAndLetsTheClaimGoWhenTheReadingFails() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        setReading(call -> {
            throw new NotAuthorizedException("not yours");
        });

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        final ValueMap properties = getProperties();
        assertEquals(ExtractionStatus.STATUS_FAILED, properties.get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertNotNull(properties.get(ExtractionStatus.MESSAGE_PROPERTY, String.class), "and the person is told why");
        assertNull(properties.get(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.class),
            "so a later parse, or somebody asking again, can take the reading");
        assertNull(properties.get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    // A broken definition throws instead of returning. Sling must not retry it: the claim is already released,
    // so a retry would claim the reading again and pay the model twice.
    @Test
    void saysSoWhenTheReadingFailsWithSomethingUnchecked() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        setReading(call -> {
            throw new IllegalStateException("a definition nobody can walk");
        });

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ExtractionStatus.STATUS_FAILED,
            getProperties().get(ExtractionStatus.STATUS_PROPERTY, String.class));
    }

    // A stop that comes after the last model call has nothing to stop. It must not stay on the pooled thread,
    // or it would stop the next job.
    @Test
    void clearsAStopThatCameAfterTheLastModelCall() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        setReading(call -> {
            new ReadingRuns().stop(this.submission.getPath());
            return null;
        });

        assertEquals(JobResult.OK, this.consumer.process(this.job));

        final ReadingRuns runs = new ReadingRuns();
        runs.begin(this.submission.getPath());
        assertFalse(runs.end(this.submission.getPath()), "the next reading on this thread is not stopped");
    }

    @Test
    void recordsAStopAndDoesNotRetry() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        setReading(call -> {
            throw new ReadingStoppedException();
        });

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ExtractionStatus.STOPPED_MESSAGE,
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
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
        inject(this.jobs, "resolverFactory", flaky);
        setReading(call -> {
            throw new ReadingStoppedException();
        });

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));
    }

    @Test
    void aReadingStoppedMidCallIsAStop() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        setReading(call -> {
            new ReadingRuns().stop(this.submission.getPath());
            throw new NotAuthorizedException("closed");
        });

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ExtractionStatus.STOPPED_MESSAGE,
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    // An interrupt from elsewhere, such as the instance shutting down, is not the submitter's stop
    @Test
    void anInterruptFromElsewhereIsNotAStop() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        setReading(call -> {
            Thread.currentThread().interrupt();
            throw new NotAuthorizedException("closed");
        });

        try {
            assertEquals(JobResult.CANCEL, this.consumer.process(this.job));
        } finally {
            Thread.interrupted();
        }

        assertEquals(ReadingJobs.COULD_NOT_READ_MESSAGE,
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    // After a stop, Read again may already have queued a new reading; this job's late give-up must not touch it
    @Test
    void leavesAReadingAnotherJobHasClaimedAlone() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        setReading(call -> {
            final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
            properties.put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_RUNNING);
            properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "job-2");
            this.context.resourceResolver().commit();
            throw new NotAuthorizedException("broken");
        });

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ExtractionStatus.STATUS_RUNNING,
            getProperties().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals("job-2", getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    // `running` was committed before the job: left as it is, the spinner would never stop
    @Test
    void givesUpAReadingThatBreaksBeforeItStarts() throws Exception
    {
        final Resource notASubmission = this.context.create().resource("/Submissions/stray", Map.of());
        setJobSubmission(notASubmission.getPath());

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        assertEquals(ExtractionStatus.STATUS_FAILED,
            notASubmission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals(List.of("readingFailed"), getFiredEvents());
    }

    @Test
    void dropsTheJobWithoutTheServiceUser() throws Exception
    {
        setJobSubmission(this.submission.getPath());
        inject(this.jobs, "resolverFactory", new TestResolverFactory(null));

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }

    // Each parse that lands queues a job, so early jobs find a submission that is not ready yet. The job is
    // retried, not dropped: it is queued before the walk that records its parse commits, so it can see its own
    // parse as unfinished. Dropping it would leave the submission at `running` forever.
    @Test
    void looksAgainWhileAParseIsStillGoing()
    {
        this.tree.createFile("completed");
        this.tree.createFile("queued");
        setJobSubmission(this.submission.getPath());

        assertEquals(JobResult.FAILED, this.consumer.process(this.job));

        Mockito.verifyNoInteractions(this.engine);
    }

    // The document is read once, however many parses landed at the same time
    @Test
    void readsASubmissionOnlyOnce() throws Exception
    {
        this.tree.createFile("completed");
        setJobSubmission(this.submission.getPath());

        final Job other = Mockito.mock(Job.class);
        Mockito.when(other.getProperty(ReadingJobs.SUBMISSION_PROPERTY, String.class))
            .thenReturn(this.submission.getPath());
        Mockito.when(other.getId()).thenReturn("job-2");

        assertEquals(JobResult.OK, this.consumer.process(this.job));
        assertEquals(JobResult.CANCEL, this.consumer.process(other));

        assertEquals(1, countReadings());
    }

    // New documents need a new reading, so parsing them removes the claim the last reading left
    @Test
    void readsASubmissionAgainOnceItsClaimHasBeenTakenDown() throws Exception
    {
        this.tree.createFile("completed");
        setJobSubmission(this.submission.getPath());
        assertEquals(JobResult.OK, this.consumer.process(this.job));

        this.submission.adaptTo(ModifiableValueMap.class).remove("extractionReadingClaimed");

        assertEquals(JobResult.OK, this.consumer.process(this.job));
        assertEquals(2, countReadings());
    }

    // Giving up is the last thing that can help this submission. When the engine refuses it too, the job still
    // ends instead of throwing an error that nobody handles.
    @Test
    void endsTheJobEvenWhenGivingUpCannotBeRecorded() throws Exception
    {
        this.tree.createFile("completed");
        setJobSubmission(this.submission.getPath());
        setReading(call -> {
            throw new NotAuthorizedException("not yours");
        });
        this.steps.put(ReadingJobs.READING_FAILED_EVENT, call -> {
            throw new InvalidStateException("the repository has moved on");
        });

        assertEquals(JobResult.CANCEL, this.consumer.process(this.job));
    }
}
