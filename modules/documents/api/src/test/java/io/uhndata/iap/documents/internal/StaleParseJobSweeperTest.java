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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.documents.api.ParseOutcome;
import io.uhndata.iap.documents.api.ParseService;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.utils.testing.TestResolverFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StaleParseJobSweeper}: which jobs are given up on, and what goes with them.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class StaleParseJobSweeperTest
{
    private static final String OLD_JOB = "86a4c102-4b6a-4933-bc33-cc02e0e26eb7";

    private static final String YOUNG_JOB = "1f0b2ec8-3b3c-4a0b-9c07-4d4f0a2c9e11";

    private static final String TARGET = "/Submissions/ab/cd/ef/aRequest/d1/v1/file";

    private static final String STAGED = "/shared-docs/x/proposal.pdf";

    /** A staging folder the parse service cannot remove, as when a worker still holds a file in it. */
    private static final String UNREMOVABLE_STAGED = "/shared-docs/busy/proposal.pdf";

    /** Minutes past the default thirty a job may go unanswered, or a settled record is kept. */
    private static final int PAST_THE_MAX_AGE = 45;

    /** Minutes well inside the thirty a job may go unanswered. */
    private static final int WITHIN_THE_MAX_AGE = 5;

    /** Minutes past the two a settled outcome is left alone, and inside the max age. */
    private static final int PAST_THE_RETRY = 5;

    /** Minutes past the day a record is kept for a folder that cannot be removed. */
    private static final int PAST_THE_FOLDER_LIMIT = 25 * 60;

    private final SlingContext context = new SlingContext();

    private final ParseOutcomeDispatcher dispatcher = new ParseOutcomeDispatcher();

    /** What the sweeper asked to be taken off the volume. */
    private final List<String> discarded = new ArrayList<>();

    /** What the handlers were told. */
    private final List<ParseOutcome> handed = new ArrayList<>();

    private StaleParseJobSweeper sweeper;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.create().resource(ParseJob.JOBS_PATH);
        this.dispatcher.bindHandler(outcome -> {
            this.handed.add(outcome);
            return true;
        });
        this.sweeper = sweeperFor(this.context.resourceResolver());
        this.sweeper.activate(Map.of());
    }

    @Test
    void givesUpOnAJobTheDaemonNeverAnsweredFor()
    {
        createJob(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(PAST_THE_MAX_AGE), TARGET);

        this.sweeper.run();

        final Resource swept = this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB));
        assertNull(swept, "the record goes once a handler has taken the outcome");
        assertEquals(1, this.handed.size());
        assertEquals(OLD_JOB, this.handed.get(0).jobId());
        assertEquals(TARGET, this.handed.get(0).target());
        assertFalse(this.handed.get(0).succeeded());
        assertTrue(this.handed.get(0).error().contains("No outcome arrived within 30 minutes"),
            this.handed.get(0).error());
    }

    // The abandoned document and anything half written beside it, such as a .tmp a killed worker never renamed
    @Test
    void takesTheStagingFolderOffTheVolumeWithIt()
    {
        createJob(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(PAST_THE_MAX_AGE), TARGET);

        this.sweeper.run();

        assertEquals(List.of(STAGED), this.discarded);
    }

    // A job that never reached the daemon has no started time, so the wait is measured from when it was queued
    @Test
    void givesUpOnAJobThatNeverLeftTheQueueEither()
    {
        final Resource job = createJob(OLD_JOB, ParseJob.STATUS_QUEUED, null, TARGET);
        edit(job).put(ParseJob.PN_CREATED, minutesAgo(PAST_THE_MAX_AGE));

        this.sweeper.run();

        assertEquals(1, this.handed.size());
    }

    @Test
    void leavesAJobThatIsStillWithinItsTime()
    {
        createJob(YOUNG_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(WITHIN_THE_MAX_AGE), TARGET);

        this.sweeper.run();

        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(YOUNG_JOB)));
        assertTrue(this.handed.isEmpty());
        assertTrue(this.discarded.isEmpty());
    }

    @Test
    void leavesAJobThatAlreadyEnded()
    {
        final Resource job = createJob(OLD_JOB, ParseJob.STATUS_COMPLETED, minutesAgo(PAST_THE_MAX_AGE), TARGET);
        edit(job).put(ParseJob.PN_OUTPUTS, new String[] { "/shared-docs/x/proposal.md" });

        this.sweeper.run();

        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
        assertTrue(this.handed.isEmpty());
    }

    // Neither timestamp means nothing to judge the wait by, and guessing would fail a parse that may be running
    @Test
    void leavesAJobWithNoTimestampsAtAll()
    {
        createJob(OLD_JOB, ParseJob.STATUS_ACTIVE, null, TARGET);

        this.sweeper.run();

        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
    }

    // A parse nobody is waiting on in the repository still has to stop saying "active" and still leaves a folder
    @Test
    void givesUpOnAPollOnlyJobWithoutHandingItToAnybody()
    {
        createJob(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(PAST_THE_MAX_AGE), null);

        this.sweeper.run();

        final Resource swept = this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB));
        assertNotNull(swept, "a job nobody claims keeps its record for polling");
        assertEquals(ParseJob.STATUS_FAILED, swept.getValueMap().get(ParseJob.PN_STATUS, String.class));
        assertEquals(List.of(STAGED), this.discarded);
        assertTrue(this.handed.isEmpty());
    }

    // A job queued with no target is nobody's to hand over, so its record is kept for polling, then dropped here
    // with its staging folder
    @Test
    void dropsTheRecordOfASettledJobNobodyCameBackFor()
    {
        createSettledJob(OLD_JOB, ParseJob.STATUS_COMPLETED, PAST_THE_MAX_AGE, null);

        this.sweeper.run();

        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
        assertEquals(List.of(STAGED), this.discarded, "and the volume goes with it");
    }

    // The same for a job whose handler refused the outcome on every sweep: once its record is old enough, it goes
    @Test
    void dropsTheRecordOfASettledJobNoHandlerTook()
    {
        createSettledJob(OLD_JOB, ParseJob.STATUS_FAILED, PAST_THE_MAX_AGE, TARGET);

        this.sweeper.run();

        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
        assertTrue(this.handed.isEmpty(), "the outcome was handed over when it settled, not now");
    }

    // The record is worth keeping while somebody might still poll it
    @Test
    void keepsTheRecordOfAJobThatSettledRecently()
    {
        createSettledJob(OLD_JOB, ParseJob.STATUS_COMPLETED, PAST_THE_RETRY, null);

        this.sweeper.run();

        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
        assertTrue(this.discarded.isEmpty());
    }

    // A handler may refuse only for now, such as for a file whose upload was not committed yet
    @Test
    void offersAnUntakenOutcomeAgain()
    {
        createSettledJob(OLD_JOB, ParseJob.STATUS_FAILED, PAST_THE_RETRY, TARGET);

        this.sweeper.run();

        assertEquals(1, this.handed.size());
        assertTrue(this.handed.get(0).offeredAgain());
        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)), "taken this time");
        assertTrue(this.discarded.isEmpty(), "the handler clears the folder once it reads the parse in");
    }

    // The request that settled it may still be handing it over
    @Test
    void leavesAnOutcomeThatSettledMomentsAgo()
    {
        createSettledJob(OLD_JOB, ParseJob.STATUS_COMPLETED, 0, TARGET);

        this.sweeper.run();

        assertTrue(this.handed.isEmpty());
    }

    // A folder a worker still holds open is retried on the next sweep, and only the record names it
    @Test
    void keepsTheRecordOfAFolderItCouldNotRemove()
    {
        final Resource job = createSettledJob(OLD_JOB, ParseJob.STATUS_COMPLETED, PAST_THE_MAX_AGE, null);
        edit(job).put(ParseJob.PN_PATH, UNREMOVABLE_STAGED);

        this.sweeper.run();

        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
    }

    // After a day something other than a worker holds the folder, and keeping the record would keep it for good
    @Test
    void dropsTheRecordOfAFolderItCouldNotRemoveForADay()
    {
        final Resource job = createSettledJob(OLD_JOB, ParseJob.STATUS_COMPLETED, PAST_THE_FOLDER_LIMIT, null);
        edit(job).put(ParseJob.PN_PATH, UNREMOVABLE_STAGED);
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);
        try {
            this.sweeper.run();

            Mockito.verify(recorder).logProblem(Mockito.any(), Mockito.any());
            Mockito.verifyNoMoreInteractions(recorder);
        } finally {
            ErrorLogger.unsetService(recorder);
        }
        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
    }

    @Test
    void reportsAnOutcomeNobodyTook()
    {
        edit(createSettledJob(OLD_JOB, ParseJob.STATUS_FAILED, PAST_THE_MAX_AGE, TARGET))
            .put(ParseJob.PN_ERROR, "No pages");
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);
        try {
            this.sweeper.run();
        } finally {
            ErrorLogger.unsetService(recorder);
        }

        Mockito.verify(recorder).logProblem(Mockito.any(), Mockito.any());
    }

    // A stopped parse is meant to go untaken: its file stopped waiting for it, so dropping it is no fault
    @Test
    void doesNotReportAStoppedOutcome()
    {
        edit(createSettledJob(OLD_JOB, ParseJob.STATUS_FAILED, PAST_THE_MAX_AGE, TARGET))
            .put(ParseJob.PN_ERROR, ParseOutcome.STOPPED_ERROR);
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);
        try {
            this.sweeper.run();
        } finally {
            ErrorLogger.unsetService(recorder);
        }

        Mockito.verifyNoInteractions(recorder);
        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
    }

    // Only polled, so nothing waits on it and dropping it is no fault
    @Test
    void doesNotReportARecordWithoutATarget()
    {
        createSettledJob(OLD_JOB, ParseJob.STATUS_COMPLETED, PAST_THE_MAX_AGE, null);
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);
        try {
            this.sweeper.run();
        } finally {
            ErrorLogger.unsetService(recorder);
        }

        Mockito.verifyNoInteractions(recorder);
        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
    }

    @Test
    void reportsEveryForgottenOutcomeWhenADeletedRecordCannotBeRead() throws Exception
    {
        for (final String jobId : List.of(OLD_JOB, YOUNG_JOB)) {
            createSettledJob(jobId, ParseJob.STATUS_FAILED, PAST_THE_MAX_AGE, TARGET);
        }
        this.context.resourceResolver().commit();
        useSession(unreadableOnceDeleted(this.context.resourceResolver()));
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);
        try {
            this.sweeper.run();
        } finally {
            ErrorLogger.unsetService(recorder);
        }

        Mockito.verify(recorder, Mockito.times(2)).logProblem(Mockito.any(), Mockito.any());
        assertFalse(this.context.resourceResolver().getResource(ParseJob.JOBS_PATH).hasChildren());
    }

    // The engine walk that asked for the stop failed and was rolled back, so the file still waits on the job
    @Test
    void handsAStoppedParseToAFileStillWaitingOnIt()
    {
        edit(createSettledJob(OLD_JOB, ParseJob.STATUS_FAILED, PAST_THE_RETRY, TARGET))
            .put(ParseJob.PN_ERROR, ParseOutcome.STOPPED_ERROR);

        this.sweeper.run();

        assertEquals(1, this.handed.size());
        assertEquals(TARGET, this.handed.get(0).target());
        assertFalse(this.handed.get(0).succeeded());
        assertEquals(ParseOutcome.STOPPED_ERROR, this.handed.get(0).error());
        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)), "taken");
    }

    // The sweep keeps using the same session, so one failed delete must not fail the deletes after it
    @Test
    void dropsTheNextRecordAfterOneItCannotDelete() throws Exception
    {
        for (final String jobId : List.of(OLD_JOB, YOUNG_JOB)) {
            createSettledJob(jobId, ParseJob.STATUS_FAILED, PAST_THE_RETRY, TARGET);
        }
        this.context.resourceResolver().commit();
        useSession(refusingTheFirstCommit(this.context.resourceResolver()));

        this.sweeper.run();

        assertEquals(2, this.handed.size());
        assertEquals(List.of(OLD_JOB), getJobsLeft(), "only the record that could not be deleted is left");
    }

    @Test
    void dropsTheNextForgottenRecordAfterOneItCannotDelete() throws Exception
    {
        for (final String jobId : List.of(OLD_JOB, YOUNG_JOB)) {
            createSettledJob(jobId, ParseJob.STATUS_COMPLETED, PAST_THE_MAX_AGE, null);
        }
        this.context.resourceResolver().commit();
        useSession(refusingTheFirstCommit(this.context.resourceResolver()));

        this.sweeper.run();

        assertEquals(List.of(OLD_JOB), getJobsLeft(), "only the record that could not be deleted is left");
        assertFalse(this.context.resourceResolver().hasChanges(), "the failed delete is not left pending");
    }

    @Test
    void failsTheNextLostJobAfterOneItCannotFail() throws Exception
    {
        for (final String jobId : List.of(OLD_JOB, YOUNG_JOB)) {
            createJob(jobId, ParseJob.STATUS_ACTIVE, minutesAgo(PAST_THE_MAX_AGE), TARGET);
        }
        this.context.resourceResolver().commit();
        useSession(refusingTheFirstCommit(this.context.resourceResolver()));

        this.sweeper.run();

        assertEquals(List.of(YOUNG_JOB), this.handed.stream().map(ParseOutcome::jobId).toList());
        assertFalse(this.context.resourceResolver().hasChanges(), "the failed write is not left pending");
    }

    @Test
    void survivesARecordItCannotDrop() throws Exception
    {
        createSettledJob(OLD_JOB, ParseJob.STATUS_COMPLETED, PAST_THE_MAX_AGE, null);
        this.context.resourceResolver().commit();
        useSession(refusingCommit(this.context.resourceResolver()));

        this.sweeper.run();

        assertEquals(List.of(STAGED), this.discarded, "the volume is cleared even so");
    }

    @Test
    void sweepsEveryStaleJobInOnePass()
    {
        createJob(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(PAST_THE_MAX_AGE), TARGET);
        createJob(YOUNG_JOB, ParseJob.STATUS_QUEUED, minutesAgo(2 * PAST_THE_MAX_AGE), TARGET);

        this.sweeper.run();

        assertEquals(2, this.handed.size());
    }

    @Test
    void doesNothingWhenTheJobsStorageIsNotThere() throws Exception
    {
        this.context.resourceResolver().delete(this.context.resourceResolver().getResource(ParseJob.JOBS_PATH));
        this.context.resourceResolver().commit();

        this.sweeper.run();

        assertTrue(this.handed.isEmpty());
    }

    @Test
    void survivesAStorageItCannotOpen() throws Exception
    {
        TestJobs.inject(this.sweeper, "resolverFactory", TestResolverFactory.withoutServiceUser());

        this.sweeper.run();

        assertTrue(this.handed.isEmpty());
    }

    @Test
    void survivesAJobNodeItCannotWrite() throws Exception
    {
        createJob(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(PAST_THE_MAX_AGE), TARGET);
        useSession(unmodifiable(this.context.resourceResolver()));

        this.sweeper.run();

        assertTrue(this.handed.isEmpty());
        assertTrue(this.discarded.isEmpty());
    }

    @Test
    void survivesACommitThatIsRefused() throws Exception
    {
        createJob(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(PAST_THE_MAX_AGE), TARGET);
        useSession(refusingCommit(this.context.resourceResolver()));

        this.sweeper.run();

        assertTrue(this.handed.isEmpty(), "nothing is handed over that was not recorded first");
        assertTrue(this.discarded.isEmpty());
    }

    @Test
    void honoursAConfiguredMaximumAge()
    {
        this.sweeper.activate(Map.of(StaleParseJobSweeper.MAX_AGE_PROPERTY, "120"));
        createJob(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(PAST_THE_MAX_AGE), TARGET);

        this.sweeper.run();

        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
    }

    @Test
    void fallsBackToTheDefaultForAnUnusableMaximumAge()
    {
        this.sweeper.activate(Map.of(StaleParseJobSweeper.MAX_AGE_PROPERTY, "half an hour"));
        createJob(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(PAST_THE_MAX_AGE), TARGET);

        this.sweeper.run();

        assertEquals(1, this.handed.size());
    }

    @Test
    void fallsBackToTheDefaultForAMaximumAgeOfZero()
    {
        this.sweeper.activate(Map.of(StaleParseJobSweeper.MAX_AGE_PROPERTY, "0"));
        createJob(YOUNG_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(WITHIN_THE_MAX_AGE), TARGET);

        this.sweeper.run();

        assertTrue(this.handed.isEmpty(), "zero would give up on everything the moment it was queued");
    }

    /** A job still waiting for its outcome, dispatched at {@code started}, or never when that is {@code null}. */
    private Resource createJob(final String jobId, final String status, final Calendar started, final String target)
    {
        final Resource job = TestJobs.createJob(this.context, jobId, status, STAGED, target);
        if (started != null) {
            edit(job).put(ParseJob.PN_STARTED, started);
        }
        return job;
    }

    /** A job that got its outcome some minutes ago. */
    private Resource createSettledJob(final String jobId, final String status, final int finishedMinutesAgo,
        final String target)
    {
        final Resource job = TestJobs.createJob(this.context, jobId, status, STAGED, target);
        edit(job).put(ParseJob.PN_FINISHED, minutesAgo(finishedMinutesAgo));
        return job;
    }

    private List<String> getJobsLeft()
    {
        final List<String> left = new ArrayList<>();
        this.context.resourceResolver().getResource(ParseJob.JOBS_PATH).getChildren()
            .forEach(job -> left.add(job.getName()));
        return left;
    }

    private static ModifiableValueMap edit(final Resource resource)
    {
        return Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class));
    }

    private static Calendar minutesAgo(final int minutes)
    {
        final Calendar when = Calendar.getInstance();
        when.add(Calendar.MINUTE, -minutes);
        return when;
    }

    /**
     * A sweeper wired to the test session, a dispatcher that records, and a parse service that records what it
     * was asked to remove rather than touching a filesystem.
     */
    private StaleParseJobSweeper sweeperFor(final ResourceResolver resolver) throws Exception
    {
        final StaleParseJobSweeper built = new StaleParseJobSweeper();
        TestJobs.inject(built, "resolverFactory", TestResolverFactory.serving(resolver));
        TestJobs.inject(built, "outcomes", this.dispatcher);
        TestJobs.inject(built, "parseService", new RecordingParseService(this.discarded));
        return built;
    }

    private void useSession(final ResourceResolver resolver) throws ReflectiveOperationException
    {
        TestJobs.inject(this.sweeper, "resolverFactory", TestResolverFactory.serving(resolver));
    }

    /** A session whose job nodes refuse to be changed. */
    private static ResourceResolver unmodifiable(final ResourceResolver resolver)
    {
        return new ResourceResolverWrapper(resolver)
        {
            @Override
            public Resource getResource(final String path)
            {
                return wrap(super.getResource(path));
            }

            private Resource wrap(final Resource resource)
            {
                if (resource == null) {
                    return null;
                }
                final List<Resource> children = new ArrayList<>();
                resource.getChildren().forEach(child -> children.add(wrap(child)));
                final Resource spy = Mockito.spy(resource);
                Mockito.doReturn(null).when(spy).adaptTo(ModifiableValueMap.class);
                Mockito.doReturn(children).when(spy).getChildren();
                return spy;
            }
        };
    }

    /** A session whose job nodes cannot be read once deleted, as in JCR; the mock session still reads them. */
    private static ResourceResolver unreadableOnceDeleted(final ResourceResolver resolver)
    {
        final Set<String> deleted = new HashSet<>();
        return new ResourceResolverWrapper(resolver)
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource resource = super.getResource(path);
                if (resource == null || !ParseJob.JOBS_PATH.equals(path)) {
                    return resource;
                }
                final List<Resource> children = new ArrayList<>();
                resource.getChildren().forEach(child -> children.add(unreadableOnceDeleted(child, deleted)));
                final Resource spy = Mockito.spy(resource);
                Mockito.doReturn(children).when(spy).getChildren();
                return spy;
            }

            @Override
            public void delete(final Resource resource) throws PersistenceException
            {
                deleted.add(resource.getPath());
                super.delete(resource);
            }
        };
    }

    private static Resource unreadableOnceDeleted(final Resource resource, final Set<String> deleted)
    {
        final Resource spy = Mockito.spy(resource);
        Mockito.doAnswer(invocation -> {
            if (deleted.contains(resource.getPath())) {
                throw new IllegalArgumentException("The node at " + resource.getPath() + " was removed");
            }
            return resource.getValueMap();
        }).when(spy).getValueMap();
        return spy;
    }

    /** A session that will not commit. */
    private static ResourceResolver refusingCommit(final ResourceResolver resolver)
    {
        return new ResourceResolverWrapper(resolver)
        {
            @Override
            public void commit() throws PersistenceException
            {
                throw new PersistenceException("the repository said no");
            }
        };
    }

    /** A session that refuses its first commit, and keeps that change pending until reverted, as Oak does. */
    private static ResourceResolver refusingTheFirstCommit(final ResourceResolver resolver)
    {
        return new ResourceResolverWrapper(resolver)
        {
            private boolean refused;

            private boolean pending;

            @Override
            public void commit() throws PersistenceException
            {
                if (!this.refused || this.pending) {
                    this.refused = true;
                    this.pending = true;
                    throw new PersistenceException("the repository said no");
                }
                super.commit();
            }

            @Override
            public void revert()
            {
                this.pending = false;
                super.revert();
            }
        };
    }

    /** Records what it was asked to take off the volume; a sweep must not need a real one. */
    private static final class RecordingParseService implements ParseService
    {
        private final List<String> discarded;

        RecordingParseService(final List<String> discarded)
        {
            this.discarded = discarded;
        }

        @Override
        public String stage(final String fileName, final InputStream content)
        {
            throw new UnsupportedOperationException("a sweep stages nothing");
        }

        @Override
        public String queue(final String path, final String target)
        {
            throw new UnsupportedOperationException("a sweep queues nothing");
        }

        @Override
        public boolean isStagedPath(final String path)
        {
            return path != null && !path.isBlank();
        }

        @Override
        public boolean discardStaging(final String stagedPath)
        {
            this.discarded.add(stagedPath);
            return !UNREMOVABLE_STAGED.equals(stagedPath);
        }
    }
}
