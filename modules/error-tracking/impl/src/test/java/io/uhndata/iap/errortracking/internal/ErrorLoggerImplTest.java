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
package io.uhndata.iap.errortracking.internal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.StreamSupport;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ErrorLoggerImpl}.
 *
 * <p>
 * Most tests run the writer on the calling thread, against a clock the test moves by hand.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ErrorLoggerImplTest
{
    /** The message most of these failures carry. */
    private static final String BOOM = "boom";

    private final SlingContext context = new SlingContext();

    private ErrorLoggerImpl logger;

    /** The test's clock. Volatile, since a running component's writer thread reads it. */
    private volatile long now = 1_000_000L;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        this.context.create().resource(ErrorLoggerService.LOGGED_ERRORS_PATH,
            "sling:resourceType", "err/LoggedErrorsHomepage");
        this.logger = build(this.context.resourceResolver());
    }

    @AfterEach
    void clearTheFacade()
    {
        ErrorLogger.setService(null);
    }

    // ---------------------------------------------------------------- what is recorded

    @Test
    void recordsWhatWasThrownAndTheStackTrace()
    {
        this.logger.logError(new IllegalStateException(BOOM));

        final ValueMap recorded = onlyError();
        assertEquals("err:LoggedFailure", recorded.get("jcr:primaryType", String.class));
        assertEquals("java.lang.IllegalStateException", recorded.get("type", String.class));
        assertEquals(List.of(BOOM), List.of(recorded.get("messages", new String[0])));
        assertTrue(recorded.get("stackTrace", "").contains("ErrorLoggerImplTest"));
        assertEquals(1L, recorded.get("occurrences", 0L));
    }

    @Test
    void recordsTheCausesToo()
    {
        this.logger.logError(new IllegalStateException("outer", new IllegalArgumentException("inner")));

        assertTrue(onlyError().get("stackTrace", "").contains("Caused by: java.lang.IllegalArgumentException: inner"));
    }

    @Test
    void recordsWhatTheCallerKnowsAboutTheCircumstances()
    {
        this.logger.logError(new IllegalStateException(BOOM),
            ErrorContext.of(ErrorLoggerImplTest.class, "doTheThing").about("/Submissions/1").actingFor("alice")
                .with("attempt", 3));

        final ValueMap recorded = onlyError();
        assertEquals("io.uhndata.iap.errortracking.internal.ErrorLoggerImplTest",
            recorded.get("component", String.class));
        assertEquals("doTheThing", recorded.get("operation", String.class));
        assertEquals(List.of("/Submissions/1"), List.of(recorded.get("subjects", new String[0])));
        assertEquals(List.of("alice"), List.of(recorded.get("actors", new String[0])));
        assertEquals("attempt: 3", recorded.get("lastContext", String.class));
    }

    @Test
    void guessesWhichOfOurClassesToBlameWhenTheCallerDoesNotSay()
    {
        this.logger.logError(new IllegalStateException(BOOM));

        assertEquals("io.uhndata.iap.errortracking.internal.ErrorLoggerImplTest",
            onlyError().get("component", String.class));
    }

    @Test
    void recordsWhenAFaultWasLastSeen()
    {
        this.logger.logError(new IllegalStateException(BOOM));

        assertEquals(this.now, onlyError().get("lastOccurrence", Calendar.class).getTimeInMillis());
    }

    @Test
    void recordsAComponentWhoseNameIsLongerThanALabelMayBe()
    {
        final String longest = "io.uhndata.iap.slacknotifications.internal.SlackNotificationConfiguration";
        assertTrue(longest.length() > 64, "the case this is about no longer exists");

        this.logger.logError(new IllegalStateException(BOOM), ErrorContext.of(longest, "publish"));

        assertEquals(longest, onlyError().get("component", String.class));
    }

    @Test
    void recordsSomethingFoundWrongThatNothingWasThrownFor()
    {
        this.logger.logProblem("unknown comparator",
            ErrorContext.of("io.uhndata.iap.Conditions", "evaluate").about("/Conditions/broken"));

        final ValueMap recorded = onlyError();
        assertEquals("err:LoggedProblem", recorded.get("jcr:primaryType", String.class));
        assertEquals("unknown comparator", recorded.get("problem", String.class));
        assertEquals(List.of("/Conditions/broken"), List.of(recorded.get("subjects", new String[0])));
        assertNull(recorded.get("stackTrace", String.class));
    }

    // ---------------------------------------------------------------- what counts as the same fault

    @Test
    void theSameFaultIsCountedNotCopied()
    {
        final Throwable error = new IllegalStateException(BOOM);

        this.logger.logError(error);
        passTheWriteWindow();
        this.logger.logError(error);

        assertEquals(2L, onlyError().get("occurrences", 0L));
    }

    @Test
    void theSameLineWithDifferentMessagesIsOneFault()
    {
        for (final String path : List.of("/first", "/second", "/third")) {
            this.logger.logError(new IllegalStateException("Cannot read " + path));
            passTheWriteWindow();
        }

        final ValueMap recorded = onlyError();
        assertEquals(3L, recorded.get("occurrences", 0L));
        assertEquals(List.of("Cannot read /third", "Cannot read /second", "Cannot read /first"),
            List.of(recorded.get("messages", new String[0])));
    }

    @Test
    void faultsThrownFromDifferentPlacesAreRecordedSeparately()
    {
        this.logger.logError(new IllegalStateException(BOOM));
        this.logger.logError(new IllegalStateException(BOOM));

        assertEquals(2, errors().size());
    }

    @Test
    void theSameCodeDoingDifferentThingsIsTwoFaults()
    {
        final Throwable error = new IllegalStateException(BOOM);

        this.logger.logError(error, ErrorContext.of("some.Thing", "readDefinitions"));
        this.logger.logError(error, ErrorContext.of("some.Thing", "computeTags"));

        assertEquals(2, errors().size());
    }

    @Test
    void differentPluginsFailingThroughTheSameFrameworkAreTwoFaults()
    {
        // One throwable, so the frames are identical, as when a framework calls into either plugin
        final Throwable error = new IllegalStateException(BOOM);

        this.logger.logError(error, ErrorContext.of("some.FirstProcessor", "computeTags"));
        this.logger.logError(error, ErrorContext.of("some.OtherProcessor", "computeTags"));

        assertEquals(2, errors().size());
    }

    @Test
    void aProblemPhraseThatQuotesSomethingIsStillRecorded()
    {
        this.logger.logProblem("unknown comparator: 'sameDay'", ErrorContext.of("some.Thing", "evaluate"));

        final ValueMap recorded = onlyError();
        assertEquals("err:LoggedProblem", recorded.get("jcr:primaryType", String.class));
        assertEquals("unknown comparator", recorded.get("problem", String.class));
        assertEquals(List.of("unknown comparator: 'sameDay'"), List.of(recorded.get("messages", new String[0])));
    }

    @Test
    void aProblemPhraseThatQuotesSomethingIsNotAllowedToDecideIdentity()
    {
        this.logger.logProblem("unknown comparator: 'sameDay'", ErrorContext.of("some.Thing", "evaluate"));
        passTheWriteWindow();
        this.logger.logProblem("unknown comparator: 'sameWeek'", ErrorContext.of("some.Thing", "evaluate"));

        assertEquals(1, errors().size());
        assertEquals(2L, onlyError().get("occurrences", 0L));
        assertEquals(List.of("unknown comparator: 'sameWeek'", "unknown comparator: 'sameDay'"),
            List.of(onlyError().get("messages", new String[0])));
    }

    @Test
    void aProblemPhraseWithNothingStableInItIsRecordedAllTheSame()
    {
        this.logger.logProblem("/Conditions/broken", ErrorContext.of("some.Thing", "evaluate"));

        final ValueMap recorded = onlyError();
        assertEquals("unspecified problem", recorded.get("problem", String.class));
        assertEquals(List.of("/Conditions/broken"), List.of(recorded.get("messages", new String[0])));
    }

    @Test
    void aPhraseWhoseHeadSaysNothingIsRecordedUnderTheSameName()
    {
        // "-42 " is made of label characters, but has no letter
        this.logger.logProblem("-42 /Conditions/broken", ErrorContext.of("some.Thing", "evaluate"));

        assertEquals("unspecified problem", onlyError().get("problem", String.class));
    }

    @Test
    void aPhraseTooLongToNameAFaultByIsCutRatherThanDropped()
    {
        this.logger.logProblem("a".repeat(100), ErrorContext.of("some.Thing", "evaluate"));

        final ValueMap recorded = onlyError();
        assertEquals("a".repeat(64), recorded.get("problem", String.class));
        assertEquals(List.of("a".repeat(100)), List.of(recorded.get("messages", new String[0])));
    }

    @Test
    void aLabelThatLooksLikeContentIsNotAllowedToDecideIdentity()
    {
        final Throwable error = new IllegalStateException(BOOM);

        this.logger.logError(error, ErrorContext.of("some.Thing", "/Submissions/1"));
        passTheWriteWindow();
        this.logger.logError(error, ErrorContext.of("some.Thing", "/Submissions/2"));

        assertEquals(1, errors().size());
        assertNull(onlyError().get("operation", String.class));
    }

    @Test
    void keepsABoundedSampleOfWhatAFaultHappenedTo()
    {
        final Throwable error = new IllegalStateException(BOOM);
        for (int i = 0; i < 25; i++) {
            this.logger.logError(error, ErrorContext.of("some.Thing", "doIt").about("/Submissions/" + i));
            passTheWriteWindow();
        }

        final List<String> subjects = List.of(onlyError().get("subjects", new String[0]));
        assertEquals(20, subjects.size());
        // Most recent first
        assertEquals("/Submissions/24", subjects.get(0));
        assertEquals(25L, onlyError().get("occurrences", 0L));
    }

    // ---------------------------------------------------------------- writing

    @Test
    void writesEverythingTalliedInOneCommit() throws ReflectiveOperationException
    {
        final CountingResolver counting = new CountingResolver(this.context.resourceResolver());
        final ErrorLoggerImpl batching = build(counting);

        batching.logError(new IllegalStateException("first"));
        batching.logError(new IllegalArgumentException("second"));

        assertEquals(2, errors().size());
        assertTrue(counting.commits <= 2, "committed " + counting.commits + " times");
    }

    @Test
    void queuesOneWritePerBurstRatherThanOnePerOccurrence() throws ReflectiveOperationException
    {
        final List<Runnable> pending = new ArrayList<>();
        final ErrorLoggerImpl deferred = build(this.context.resourceResolver());
        TestResolvers.set(deferred, "writer", (Executor) pending::add);
        final Throwable error = new IllegalStateException(BOOM);

        for (int i = 0; i < 50; i++) {
            deferred.logError(error);
        }

        assertEquals(1, pending.size());

        pending.forEach(Runnable::run);
        assertEquals(50L, onlyError().get("occurrences", 0L));
    }

    @Test
    void printsTheStackTraceOnceRatherThanPerOccurrence() throws ReflectiveOperationException
    {
        final AtomicInteger printed = new AtomicInteger();
        final Throwable counting = new IllegalStateException(BOOM)
        {
            private static final long serialVersionUID = 1L;

            @Override
            public void printStackTrace(final PrintWriter writer)
            {
                printed.incrementAndGet();
                super.printStackTrace(writer);
            }
        };
        final ErrorLoggerImpl deferred = build(this.context.resourceResolver());
        TestResolvers.set(deferred, "writer", (Executor) runnable -> {
            // Nothing runs the writer until the test flushes, so the whole burst is one tally
        });

        for (int i = 0; i < 5; i++) {
            deferred.logError(counting);
        }

        assertEquals(1, printed.get());

        deferred.flush();
        assertEquals(5L, onlyError().get("occurrences", 0L));
        assertTrue(onlyError().get("stackTrace", "").contains("ErrorLoggerImplTest"));
    }

    @Test
    void writesTheTailOfABurstWithoutWaitingForAnotherFailure() throws Exception
    {
        final AtomicInteger commits = new AtomicInteger();
        final CountDownLatch written = new CountDownLatch(2);
        final ErrorLoggerImpl running = new ErrorLoggerImpl();
        TestResolvers.inject(running, new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public void commit() throws PersistenceException
            {
                super.commit();
                commits.incrementAndGet();
                written.countDown();
            }

            @Override
            public void close()
            {
                // The test context owns this resolver
            }
        });
        TestResolvers.set(running, "clock", (LongSupplier) () -> this.now);
        TestResolvers.set(running, "writeInterval", 20L);
        running.activate();
        final ScheduledExecutorService writer = (ScheduledExecutorService) TestResolvers.get(running, "ownWriter");
        try {
            final Throwable error = new IllegalStateException(BOOM);
            // Waits for the writer after each occurrence. Otherwise both can join one tally, written once, and the
            // timer then finds nothing to write
            running.logError(error);
            awaitTheWriter(writer);
            assertEquals(1, commits.get(), "the first occurrence of the burst was never written");

            running.logError(error);
            awaitTheWriter(writer);
            assertEquals(1, commits.get(), "the tail went out inside the window instead of being held back");

            // Nothing is queued now, so only the writer's timer can write the second occurrence
            passTheWriteWindow();

            assertTrue(written.await(5, TimeUnit.SECONDS), "the tail of the burst was never written");
        } finally {
            running.deactivate();
        }
        assertEquals(2L, onlyError().get("occurrences", 0L));
    }

    @Test
    void stoppingWritesOutEvenWhatWasNotDueYet() throws ReflectiveOperationException
    {
        final ErrorLoggerImpl running = new ErrorLoggerImpl();
        TestResolvers.inject(running, this.context.resourceResolver());
        running.activate();
        final Throwable error = new IllegalStateException(BOOM);
        running.logError(error);
        running.logError(error);
        running.logError(error);

        running.deactivate();

        assertEquals(3L, onlyError().get("occurrences", 0L));
    }

    @Test
    void doesNotWriteTheSameFaultOverAndOverWithinTheWindow()
    {
        final Throwable error = new IllegalStateException(BOOM);
        this.logger.logError(error);
        this.logger.logError(error);
        this.logger.logError(error);

        // The later occurrences wait for the interval
        assertEquals(1L, onlyError().get("occurrences", 0L));

        passTheWriteWindow();
        this.logger.flush();

        assertEquals(3L, onlyError().get("occurrences", 0L));
    }

    @Test
    void theLatestCircumstancesReplaceTheEarlierOnesOnAnAlreadyRecordedFault()
    {
        final Throwable error = new IllegalStateException(BOOM);

        this.logger.logError(error, ErrorContext.of("some.Thing", "doIt").with("attempt", 1));
        passTheWriteWindow();
        this.logger.logError(error, ErrorContext.of("some.Thing", "doIt").with("attempt", 2));

        assertEquals("attempt: 2", onlyError().get("lastContext", String.class));
    }

    @Test
    void aFaultRecordedWhileAnEarlierBatchIsFailingIsFoldedInRatherThanLost() throws Exception
    {
        // Two threads: the second occurrence arrives after the batch is taken out and before it is put back
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch tallied = new CountDownLatch(1);
        final ErrorLoggerImpl racing = build(new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public void commit() throws PersistenceException
            {
                writing.countDown();
                try {
                    tallied.await(5, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new PersistenceException("refused");
            }

            @Override
            public void close()
            {
                // The test context owns this resolver
            }
        });
        // A write the other thread asks for waits for the test to run it, as on the real writer's one thread. Run on
        // the other thread, it would block in the commit above
        final Thread testThread = Thread.currentThread();
        final List<Runnable> waiting = new ArrayList<>();
        TestResolvers.set(racing, "writer", (Executor) task -> {
            if (Thread.currentThread() == testThread) {
                task.run();
            } else {
                waiting.add(task);
            }
        });
        final Throwable error = new IllegalStateException(BOOM);

        final Thread other = new Thread(() -> {
            try {
                writing.await(5, TimeUnit.SECONDS);
                racing.logError(error, ErrorContext.of("some.Thing", "doIt").about("/second"));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                tallied.countDown();
            }
        });
        other.start();
        racing.logError(error, ErrorContext.of("some.Thing", "doIt").about("/first"));
        other.join(5_000);
        waiting.forEach(Runnable::run);

        // The repository can be written to again
        TestResolvers.set(racing, "records",
            new RecordWriter(TestResolvers.factory(this.context.resourceResolver())));
        passTheWriteWindow();
        racing.flush();

        // The count is not asserted exactly: the mock repository does not roll back a failed commit, so the failed
        // attempts left their own counts
        assertEquals(Set.of("/first", "/second"), Set.of(onlyError().get("subjects", new String[0])));
        assertTrue(onlyError().get("occurrences", 0L) >= 2L);
    }

    @Test
    void nothingIsLostWhenAWriteFails() throws ReflectiveOperationException
    {
        final ErrorLoggerImpl failing = build(this.context.resourceResolver());
        TestResolvers.set(failing, "records", new RecordWriter(TestResolvers.factory(null)));

        failing.logError(new IllegalStateException(BOOM));
        assertTrue(errors().isEmpty());

        // The repository can be written to again
        TestResolvers.set(failing, "records",
            new RecordWriter(TestResolvers.factory(this.context.resourceResolver())));
        passTheWriteWindow();
        failing.flush();

        assertEquals(1L, onlyError().get("occurrences", 0L));
    }

    @Test
    void stopsTryingWhenWritingKeepsFailing() throws ReflectiveOperationException
    {
        final CountingResolver counting = new CountingResolver(this.context.resourceResolver());
        counting.failCommits = true;
        final ErrorLoggerImpl failing = build(counting);

        // Four faults, but the writer pauses after the third failed write
        for (int i = 0; i < 4; i++) {
            failing.logError(new IllegalStateException(BOOM), ErrorContext.of("some.Thing", "attempt" + i));
            passTheWriteWindow();
        }
        assertEquals(ErrorLoggerImpl.FAILURES_BEFORE_PAUSE, counting.commits,
            "kept trying " + counting.commits + " times");

        // After the pause, it writes again
        this.now += ErrorLoggerImpl.PAUSE_MS;
        failing.logError(new IllegalStateException(BOOM), ErrorContext.of("some.Thing", "afterThePause"));

        assertEquals(ErrorLoggerImpl.FAILURES_BEFORE_PAUSE + 1, counting.commits);
    }

    @Test
    void aWriteThatFailsOutrightLeavesTheWriterRunning() throws ReflectiveOperationException
    {
        // Not a failed commit, but the writing itself throwing
        final ErrorLoggerImpl brokenOnce = build(this.context.resourceResolver());
        final AtomicInteger ticks = new AtomicInteger();
        TestResolvers.set(brokenOnce, "clock", (LongSupplier) () -> {
            // The second reading is the one the writing takes; the first is the tally's
            if (ticks.incrementAndGet() == 2) {
                throw new IllegalStateException("no clock");
            }
            return this.now;
        });

        assertDoesNotThrow(() -> brokenOnce.logError(new IllegalStateException(BOOM)));
        assertTrue(errors().isEmpty());

        // The next recording writes both tallies
        brokenOnce.logError(new IllegalStateException("after"));
        assertEquals(2, errors().size());
    }

    @Test
    void stoppingSurvivesAWriteThatFailsOutright() throws ReflectiveOperationException
    {
        final ErrorLoggerImpl running = new ErrorLoggerImpl();
        TestResolvers.inject(running, this.context.resourceResolver());
        running.activate();
        TestResolvers.set(running, "clock", (LongSupplier) () -> {
            throw new IllegalStateException("no clock");
        });
        running.logError(new IllegalStateException(BOOM));

        assertDoesNotThrow(running::deactivate);
    }

    @Test
    void survivesAMissingContainer() throws PersistenceException
    {
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(ErrorLoggerService.LOGGED_ERRORS_PATH));

        assertDoesNotThrow(() -> this.logger.logError(new IllegalStateException(BOOM)));
    }

    @Test
    void survivesARecordedErrorItCannotUpdate() throws ReflectiveOperationException
    {
        final Throwable error = new IllegalStateException(BOOM);
        this.logger.logError(error);
        final ErrorLoggerImpl readOnly = build(readOnlyErrors(this.context.resourceResolver()));

        passTheWriteWindow();
        assertDoesNotThrow(() -> readOnly.logError(error));

        assertEquals(1L, onlyError().get("occurrences", 0L));
    }

    @Test
    void survivesAnUnreachableRepository() throws ReflectiveOperationException
    {
        final ErrorLoggerImpl unreachable = build(null);

        assertDoesNotThrow(() -> unreachable.logError(new IllegalStateException(BOOM)));
    }

    @Test
    void keepsOnlySoManyFaultsWaitingToBeWritten() throws ReflectiveOperationException
    {
        final ErrorLoggerImpl unreachable = build(null);

        for (int i = 0; i < ErrorLoggerImpl.MAX_PENDING + 50; i++) {
            unreachable.logError(new IllegalStateException(BOOM), ErrorContext.of("some.Thing", "operation" + i));
        }

        assertTrue(unreachable.getDroppedCount() > 0);
    }

    // ---------------------------------------------------------------- guards and lifecycle

    @Test
    void doesNotRecordAFailureRaisedWhileRecording() throws ReflectiveOperationException
    {
        final ErrorLoggerImpl recursive = build(this.context.resourceResolver());
        final ResourceResolver reentrant = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public void commit()
            {
                recursive.logError(new IllegalStateException("raised while recording"));
            }

            @Override
            public void close()
            {
                // The test context owns this resolver
            }
        };
        TestResolvers.set(recursive, "records", new RecordWriter(TestResolvers.factory(reentrant)));

        assertDoesNotThrow(() -> recursive.logError(new IllegalStateException(BOOM)));
    }

    @Test
    void ignoresNothingToRecord()
    {
        this.logger.logError(null);
        this.logger.logProblem(null, ErrorContext.NO_CONTEXT);
        this.logger.logProblem("   ", ErrorContext.NO_CONTEXT);

        assertTrue(errors().isEmpty());
    }

    @Test
    void takesNoContextToMeanNothingIsKnown()
    {
        this.logger.logError(new IllegalStateException(BOOM), null);
        passTheWriteWindow();
        this.logger.logProblem("unknown comparator", null);

        assertEquals(2, errors().size());
    }

    @Test
    void publishesItselfToTheFacadeWhileItRuns() throws ReflectiveOperationException
    {
        final ErrorLoggerImpl running = new ErrorLoggerImpl();
        TestResolvers.inject(running, this.context.resourceResolver());
        running.activate();
        try {
            ErrorLogger.logError(new IllegalStateException(BOOM));
        } finally {
            // Stopping writes out what was waiting
            running.deactivate();
        }
        assertEquals(1, errors().size());

        ErrorLogger.logError(new IllegalStateException("after"));
        assertEquals(1, errors().size());
    }

    @Test
    void survivesAFailureItCannotEvenDescribe()
    {
        final Throwable unprintable = new IllegalStateException(BOOM)
        {
            private static final long serialVersionUID = 1L;

            @Override
            public String getMessage()
            {
                throw new IllegalStateException("not while I am broken");
            }
        };

        assertDoesNotThrow(() -> this.logger.logError(unprintable));
        assertTrue(errors().isEmpty());
    }

    @Test
    void givesUpWaitingForTheLastTalliesRatherThanHangingTheShutdown() throws Exception
    {
        final ErrorLoggerImpl running = new ErrorLoggerImpl();
        TestResolvers.inject(running, this.context.resourceResolver());
        running.activate();
        TestResolvers.set(running, "shutdownWait", 0L);
        // Hold the writer's thread, so that stopping has to give up waiting for it
        final CountDownLatch holding = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ScheduledExecutorService writer =
            (ScheduledExecutorService) TestResolvers.get(running, "ownWriter");
        writer.execute(() -> {
            holding.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(holding.await(5, TimeUnit.SECONDS), "the writer never picked the task up");

        try {
            assertDoesNotThrow(running::deactivate);
        } finally {
            release.countDown();
        }
    }

    @Test
    void stoppingWhileInterruptedLeavesTheThreadInterrupted() throws Exception
    {
        final ErrorLoggerImpl running = new ErrorLoggerImpl();
        TestResolvers.inject(running, this.context.resourceResolver());
        running.activate();
        TestResolvers.set(running, "shutdownWait", 2_000L);
        // Hold the writer's thread, so that stopping has to wait for it. The task must be running before the
        // interrupt, or the executor terminates at once
        final CountDownLatch holding = new CountDownLatch(1);
        final CountDownLatch occupied = new CountDownLatch(1);
        final ScheduledExecutorService writer =
            (ScheduledExecutorService) TestResolvers.get(running, "ownWriter");
        writer.execute(() -> {
            holding.countDown();
            try {
                occupied.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(holding.await(5, TimeUnit.SECONDS), "the writer never picked the task up");
        Thread.currentThread().interrupt();

        try {
            running.deactivate();

            assertTrue(Thread.interrupted(), "the interrupt was swallowed rather than restored");
        } finally {
            // Release the thread, which would otherwise stay parked for five seconds
            occupied.countDown();
        }
    }

    @Test
    void stoppingWithoutHavingStartedIsHarmless()
    {
        assertDoesNotThrow(() -> new ErrorLoggerImpl().deactivate());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A logger writing on the calling thread, against a clock the test controls.
     *
     * @param resolver the repository to record into, {@code null} for one that cannot be reached
     * @return a configured component
     * @throws ReflectiveOperationException if a field the test sets does not exist
     */
    private ErrorLoggerImpl build(final ResourceResolver resolver) throws ReflectiveOperationException
    {
        final ErrorLoggerImpl built = new ErrorLoggerImpl();
        final ResourceResolverFactory factory = TestResolvers.factory(resolver);
        TestResolvers.set(built, "resolverFactory", factory);
        TestResolvers.set(built, "records", new RecordWriter(factory));
        TestResolvers.set(built, "writer", (Executor) Runnable::run);
        TestResolvers.set(built, "clock", (LongSupplier) () -> this.now);
        return built;
    }

    /** Moves the clock past the interval within which one fault is written only once. */
    private void passTheWriteWindow()
    {
        this.now += ErrorLoggerImpl.WRITE_INTERVAL_MS + 1;
    }

    /**
     * Waits until the writer has run every task queued so far. The writer is one thread, so once a task of the
     * test's own has run, every task queued before it has finished.
     *
     * @param writer the writer the component made for itself
     * @throws Exception if the writer does not get through its queue in time
     */
    private static void awaitTheWriter(final ScheduledExecutorService writer) throws Exception
    {
        writer.submit(() -> {
            // Nothing to do
        }).get(5, TimeUnit.SECONDS);
    }

    /**
     * Every recorded error.
     *
     * @return the nodes under the container
     */
    private List<Resource> errors()
    {
        final Resource home = this.context.resourceResolver().getResource(ErrorLoggerService.LOGGED_ERRORS_PATH);
        return home == null ? List.of() : StreamSupport.stream(home.getChildren().spliterator(), false).toList();
    }

    /**
     * The single recorded error, failing the test when there is not exactly one.
     *
     * @return its properties
     */
    private ValueMap onlyError()
    {
        final List<Resource> found = errors();
        assertEquals(1, found.size(), "expected exactly one recorded error");
        return found.get(0).getValueMap();
    }

    /**
     * A resolver that may be read but not written, standing in for a session whose privileges are wrong.
     *
     * @param resolver the real resolver
     * @return a resolver whose recorded errors cannot be modified
     */
    private static ResourceResolver readOnlyErrors(final ResourceResolver resolver)
    {
        return new ResourceResolverWrapper(resolver)
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource home = super.getResource(path);
                return home == null ? null : new ResourceWrapper(home)
                {
                    @Override
                    public Resource getChild(final String name)
                    {
                        final Resource child = super.getChild(name);
                        return child == null ? null : new ResourceWrapper(child)
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

            @Override
            public void close()
            {
                // The test context owns this resolver
            }
        };
    }

    /** Counts how often the recording session commits, and can refuse to. */
    private static final class CountingResolver extends ResourceResolverWrapper
    {
        private int commits;

        private boolean failCommits;

        CountingResolver(final ResourceResolver resolver)
        {
            super(resolver);
        }

        @Override
        public void commit() throws PersistenceException
        {
            this.commits++;
            if (this.failCommits) {
                throw new PersistenceException("refused");
            }
            super.commit();
        }

        @Override
        public void close()
        {
            // The test context owns this resolver
        }
    }
}
