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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
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

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ReadingJobs}: when a reading job reads a submission, waits, or gives up.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReadingJobsTest
{
    private static final String JOB_ID = "job-1";

    private static final String OTHER_JOB_ID = "job-2";

    private static final String READING_STARTED = ReadingJobs.READING_STARTED_EVENT;

    private static final String READING_FAILED = ReadingJobs.READING_FAILED_EVENT;

    private static final String COMPLETED = "completed";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final WorkflowEngine engine = Mockito.mock(WorkflowEngine.class);

    private final Job job = Mockito.mock(Job.class);

    private final ReadingJobs jobs = new ReadingJobs();

    private final StandInReading reading = new StandInReading();

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
        inject("resolverFactory", new TestResolverFactory(this.context.resourceResolver()));
        inject("engine", this.engine);
        inject("runs", new ReadingRuns());
        Mockito.when(this.job.getId()).thenReturn(JOB_ID);
        this.steps.put(READING_STARTED, call -> runStep(new ClaimReadingHandler(), call));
        this.steps.put(READING_FAILED, call -> runStep(new GiveUpReadingHandler(), call));
        Mockito.when(this.engine.receiveEvent(Mockito.any(), Mockito.any())).thenAnswer(
            call -> this.steps.get(call.<WorkflowEvent>getArgument(1).getName()).answer(call));
    }

    private void inject(final String name, final Object value) throws Exception
    {
        final Field field = ReadingJobs.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.jobs, value);
    }

    /** Runs a status step as the engine would: through its own session, committed when the step succeeds. */
    private Object runStep(final ServiceTaskHandler handler, final InvocationOnMock call) throws Exception
    {
        final Resource target =
            this.context.resourceResolver().getResource(call.<Resource>getArgument(0).getPath());
        handler.execute(TaskContexts.of(target, call.<WorkflowEvent>getArgument(1).getPayload(), new HashMap<>()));
        this.context.resourceResolver().commit();
        return null;
    }

    private JobResult runFor(final String path)
    {
        Mockito.when(this.job.getProperty(ReadingJobs.SUBMISSION_PROPERTY, String.class)).thenReturn(path);
        return this.jobs.run(this.job, this.reading);
    }

    private JobResult runForTheSubmission()
    {
        return runFor(this.submission.getPath());
    }

    private void askedFromARequest()
    {
        Mockito.when(this.job.getProperty(ReadingJobs.REQUESTED_PROPERTY, Boolean.class)).thenReturn(Boolean.TRUE);
    }

    private List<String> getFiredEvents() throws Exception
    {
        final ArgumentCaptor<WorkflowEvent> events = ArgumentCaptor.forClass(WorkflowEvent.class);
        Mockito.verify(this.engine, Mockito.atLeast(0)).receiveEvent(Mockito.any(), events.capture());
        return events.getAllValues().stream().map(WorkflowEvent::getName).toList();
    }

    private ValueMap getProperties()
    {
        return this.context.resourceResolver().getResource(this.submission.getPath()).getValueMap();
    }

    private String getStatus()
    {
        return getProperties().get(ExtractionStatus.STATUS_PROPERTY, String.class);
    }

    private String getMessage()
    {
        return getProperties().get(ExtractionStatus.MESSAGE_PROPERTY, String.class);
    }

    private String getClaim()
    {
        return getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class);
    }

    private void setStatus(final String status)
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY, status);
    }

    private void claim(final String jobId) throws Exception
    {
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.TRUE);
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, jobId);
        this.context.resourceResolver().commit();
    }

    @Test
    void claimsTheReadingThenReads() throws Exception
    {
        assertEquals(JobResult.OK, runForTheSubmission());

        assertEquals(List.of(READING_STARTED), getFiredEvents());
        assertEquals(List.of(JOB_ID), this.reading.readBy);
        assertEquals(JOB_ID, getClaim());
    }

    // Sling runs an unfinished job again after a restart, with the same id
    @Test
    void readsWhenTheClaimIsAlreadyItsOwn() throws Exception
    {
        claim(JOB_ID);

        assertEquals(JobResult.OK, runForTheSubmission());

        assertEquals(List.of(JOB_ID), this.reading.readBy);
    }

    @Test
    void dropsTheJobWhenAnotherJobHoldsTheClaim() throws Exception
    {
        claim(OTHER_JOB_ID);

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        assertEquals(List.of(READING_STARTED), getFiredEvents());
        assertEquals(OTHER_JOB_ID, getClaim());
        assertEquals(List.of(), this.reading.readBy);
    }

    // Two jobs both found the claim unset and both claimed; the engine refuses the second commit
    @Test
    void dropsTheJobWhoseClaimLostTheRace() throws Exception
    {
        this.steps.put(READING_STARTED, call -> {
            throw new InvalidStateException("Somebody else changed this at the same time");
        });

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        assertEquals(List.of(READING_STARTED), getFiredEvents(), "and it does not give up the winner's reading");
    }

    // `running` was committed before the job: left as it is, the spinner would never stop
    @Test
    void givesUpWhenTheEngineRefusesTheClaimForAnotherReason() throws Exception
    {
        this.steps.put(READING_STARTED, call -> {
            throw new NotAuthorizedException("not the extraction user");
        });

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        assertEquals(List.of(), this.reading.readBy);
        assertEquals(ExtractionStatus.STATUS_FAILED, getStatus());
        assertEquals(ReadingJobs.COULD_NOT_READ_MESSAGE, getMessage());
    }

    // The job can start before the request that queued it commits, so the old claim may still be there
    @Test
    void waitsForTheRequestThatQueuedItToCommit() throws Exception
    {
        setStatus(ExtractionStatus.STATUS_DONE);
        claim("job-0");
        askedFromARequest();

        assertEquals(JobResult.FAILED, runForTheSubmission());

        assertEquals(List.of(), getFiredEvents());
    }

    @Test
    void readsOnceTheRequestIsCommitted()
    {
        setStatus(ExtractionStatus.STATUS_RUNNING);
        askedFromARequest();

        assertEquals(JobResult.OK, runForTheSubmission());

        assertEquals(List.of(JOB_ID), this.reading.readBy);
    }

    // Each parse that lands queues a job, and the job is queued before its own parse commits. Dropped, it would
    // leave the submission at `running` for good.
    @Test
    void looksAgainWhileAParseIsStillGoing()
    {
        this.tree.createFile(COMPLETED);
        this.tree.createFile("queued");

        assertEquals(JobResult.FAILED, runForTheSubmission());

        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void stopsWithoutClaimingWhenThereIsNothingLeftToRead()
    {
        this.reading.alreadyRead = true;

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        Mockito.verifyNoInteractions(this.engine);
    }

    // Read anyway, it would find nothing to do and leave `running` on the submission for good
    @Test
    void givesUpWithTheReasonTheReadingCannotStart() throws Exception
    {
        this.reading.reasonNotToStart = ReadingJobs.NOT_SET_UP_MESSAGE;

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        assertEquals(List.of(READING_FAILED), getFiredEvents());
        assertEquals(ExtractionStatus.STATUS_FAILED, getStatus());
        assertEquals(ReadingJobs.NOT_SET_UP_MESSAGE, getMessage());
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.class));
    }

    @Test
    void givesUpWhenTheSchemaVersionCannotBeRead() throws Exception
    {
        this.submission.adaptTo(ModifiableValueMap.class).put("schemaVersion", "5c4e3a1b-0000-0000-0000-000000000000");
        this.context.resourceResolver().commit();

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        assertEquals(List.of(READING_FAILED), getFiredEvents());
        assertEquals(ExtractionStatus.STATUS_FAILED, getStatus());
        assertEquals(ReadingJobs.COULD_NOT_READ_MESSAGE, getMessage());
    }

    // `running` was committed before the job: left as it is, the spinner would never stop
    @Test
    void givesUpAReadingThatBreaksBeforeItStarts() throws Exception
    {
        final Resource notASubmission = this.context.create().resource("/Submissions/stray", Map.of());

        assertEquals(JobResult.CANCEL, runFor(notASubmission.getPath()));

        assertEquals(ExtractionStatus.STATUS_FAILED,
            notASubmission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals(List.of(READING_FAILED), getFiredEvents());
    }

    @Test
    void dropsAJobThatNamesNoSubmission()
    {
        assertEquals(JobResult.CANCEL, runFor(null));

        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void dropsAJobForASubmissionThatIsGone()
    {
        assertEquals(JobResult.CANCEL, runFor("/Submissions/aa/bb/cc/gone"));

        Mockito.verifyNoInteractions(this.engine);
    }

    @Test
    void dropsTheJobWithoutTheServiceUser() throws Exception
    {
        inject("resolverFactory", new TestResolverFactory(null));

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        Mockito.verifyNoInteractions(this.engine);
    }

    // The engine undoes the whole walk, so `running` and this job's claim are still there. Left alone, nothing
    // could ever read the submission again.
    @Test
    void saysSoAndLetsTheClaimGoWhenTheReadingFails() throws Exception
    {
        this.reading.failure = new NotAuthorizedException("not yours");

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        assertEquals(ExtractionStatus.STATUS_FAILED, getStatus());
        assertEquals(ReadingJobs.COULD_NOT_READ_MESSAGE, getMessage());
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.class),
            "so a later parse, or somebody asking again, can take the reading");
        assertNull(getClaim());
    }

    // Not retried: the claim is already released, so a retry would claim it again and pay the model twice
    @Test
    void saysSoWhenTheReadingFailsWithSomethingUnchecked()
    {
        this.reading.failure = new IllegalStateException("a definition nobody can walk");

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        assertEquals(ExtractionStatus.STATUS_FAILED, getStatus());
    }

    // Giving up is the last thing that can help this submission. When the engine refuses it too, the job still ends.
    @Test
    void endsTheJobEvenWhenGivingUpCannotBeRecorded()
    {
        this.reading.failure = new NotAuthorizedException("not yours");
        this.steps.put(READING_FAILED, call -> {
            throw new InvalidStateException("the repository has moved on");
        });

        assertEquals(JobResult.CANCEL, runForTheSubmission());
    }

    // After a stop, Read again may already have queued a new reading; this job's late give-up must not touch it
    @Test
    void leavesAReadingAnotherJobHasClaimedAlone() throws Exception
    {
        this.reading.onRead = () -> {
            setStatus(ExtractionStatus.STATUS_RUNNING);
            this.submission.adaptTo(ModifiableValueMap.class)
                .put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, OTHER_JOB_ID);
            this.context.resourceResolver().commit();
        };
        this.reading.failure = new NotAuthorizedException("broken");

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        assertEquals(ExtractionStatus.STATUS_RUNNING, getStatus());
        assertEquals(OTHER_JOB_ID, getClaim());
    }

    @Test
    void recordsAStopAndDoesNotRetry()
    {
        this.reading.failure = new ReadingStoppedException();

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        assertEquals(ExtractionStatus.STOPPED_MESSAGE, getMessage());
    }

    @Test
    void aReadingStoppedMidCallIsAStop()
    {
        this.reading.onRead = () -> new ReadingRuns().stop(this.submission.getPath());
        this.reading.failure = new NotAuthorizedException("closed");

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        assertEquals(ExtractionStatus.STOPPED_MESSAGE, getMessage());
    }

    // An interrupt from elsewhere, such as the instance shutting down, is not the submitter's stop
    @Test
    void anInterruptFromElsewhereIsNotAStop()
    {
        this.reading.onRead = () -> Thread.currentThread().interrupt();
        this.reading.failure = new NotAuthorizedException("closed");

        try {
            assertEquals(JobResult.CANCEL, runForTheSubmission());
        } finally {
            Thread.interrupted();
        }

        assertEquals(ReadingJobs.COULD_NOT_READ_MESSAGE, getMessage());
    }

    // The stop is recorded through a new session, since the job's own is closed by then
    @Test
    @SuppressWarnings("unchecked")
    void aStopThatCannotBeRecordedIsStillNotRetried() throws Exception
    {
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
        this.reading.failure = new ReadingStoppedException();

        assertEquals(JobResult.CANCEL, runForTheSubmission());

        assertEquals(List.of(READING_STARTED), getFiredEvents());
    }

    // A stop after the last model call has nothing to stop. Left on the pooled thread, it would stop the next job.
    @Test
    void clearsAStopThatCameAfterTheLastModelCall()
    {
        this.reading.onRead = () -> new ReadingRuns().stop(this.submission.getPath());

        assertEquals(JobResult.OK, runForTheSubmission());

        final ReadingRuns runs = new ReadingRuns();
        runs.begin(this.submission.getPath());
        assertFalse(runs.end(this.submission.getPath()), "the next reading on this thread is not stopped");
    }

    /** Something to do while reading, before the reading ends or fails. */
    private interface ReadStep
    {
        void run() throws Exception;
    }

    /** A reading whose answers each test sets. */
    private static final class StandInReading implements ReadingJob
    {
        private final List<String> readBy = new ArrayList<>();

        private boolean alreadyRead;

        private String reasonNotToStart;

        private ReadStep onRead = () -> { };

        private Exception failure;

        @Override
        public boolean isAlreadyRead(final Submission submission)
        {
            return this.alreadyRead;
        }

        @Override
        public String findReasonNotToStart(final ResourceResolver resolver, final Submission submission)
        {
            return this.reasonNotToStart;
        }

        @Override
        public void read(final Resource submission, final String jobId) throws WorkflowException
        {
            this.readBy.add(jobId);
            try {
                this.onRead.run();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
            if (this.failure instanceof WorkflowException) {
                throw (WorkflowException) this.failure;
            }
            if (this.failure != null) {
                throw (RuntimeException) this.failure;
            }
        }
    }
}
