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
 * The writer runs on the calling thread and the clock is a field the test moves by hand, so that every assertion is
 * deterministic: the component is asynchronous in production for reasons that have nothing to do with what is being
 * asserted here, and a test that waited on a real one would only be slow and flaky.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ErrorLoggerImplTest
{
    /** The message most of these failures carry; what it says never matters, only that it is the same one. */
    private static final String BOOM = "boom";

    private final SlingContext context = new SlingContext();

    private ErrorLoggerImpl logger;

    private long now = 1_000_000L;

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
        // Several classes in this build are already past sixty characters, and the component is the most useful
        // field of the record: a label-length limit here would drop it for exactly the classes that say the most
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
        // The message is not part of what identifies a fault: broken code naming two different paths is one thing
        // to fix, not two, and keeping them apart is what would let one systematic failure fill the container
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
        // This is why the component takes part in the fingerprint at all: a framework calling into plugins produces
        // the identical frame list whichever plugin is the broken one
        final Throwable error = new IllegalStateException(BOOM);

        this.logger.logError(error, ErrorContext.of("some.FirstProcessor", "computeTags"));
        this.logger.logError(error, ErrorContext.of("some.OtherProcessor", "computeTags"));

        assertEquals(2, errors().size());
    }

    @Test
    void aProblemPhraseThatQuotesSomethingIsStillRecorded()
    {
        // Dropping this would be a silent failure in the one component that exists to prevent them. So the stable
        // head of the phrase names the fault, and the phrase itself is kept the way a throwable's message is
        this.logger.logProblem("unknown comparator: 'sameDay'", ErrorContext.of("some.Thing", "evaluate"));

        final ValueMap recorded = onlyError();
        assertEquals("err:LoggedProblem", recorded.get("jcr:primaryType", String.class));
        assertEquals("unknown comparator", recorded.get("problem", String.class));
        assertEquals(List.of("unknown comparator: 'sameDay'"), List.of(recorded.get("messages", new String[0])));
    }

    @Test
    void aProblemPhraseThatQuotesSomethingIsNotAllowedToDecideIdentity()
    {
        // One record naming the fault, with the phrases as a bounded sample — never one record per quoted value, in
        // a store that never deletes anything
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
        // "-42 " passes for label characters without being anything a reader could act on
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
        // A path passed as an operation would otherwise mint a record per path, in a store that never deletes
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
        // Most recent first, so the sample says what is going wrong now rather than what went wrong first
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
        // Two faults, and at most one commit each — never one commit per occurrence, which is what recording from
        // inside a loop used to cost
        assertTrue(counting.commits <= 2, "committed " + counting.commits + " times");
    }

    @Test
    void queuesOneWritePerBurstRatherThanOnePerOccurrence() throws ReflectiveOperationException
    {
        // The tally is bounded, the executor's queue is not: one task per occurrence lets a failing loop pile up
        // hundreds of thousands of no-op tasks behind the single write they are all waiting for
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
        // Printing a cause chain builds up to 64 KB of string, and only the occurrence that starts a tally keeps one
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
            // Written by hand below, so that the whole burst is one tally
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
        // due() deliberately holds back a fault written within the window, so the last occurrences of a burst are
        // due only once it is over — and nothing arrives then to notice. Without a clock of its own the writer would
        // leave one occurrence and a stale date in the repository for as long as the instance kept running
        final CountDownLatch written = new CountDownLatch(2);
        final ErrorLoggerImpl running = new ErrorLoggerImpl();
        TestResolvers.inject(running, new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public void commit() throws PersistenceException
            {
                super.commit();
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
        try {
            final Throwable error = new IllegalStateException(BOOM);
            running.logError(error);
            running.logError(error);
            // Nothing else fails from here on: only the writer's own clock can notice that the second occurrence is
            // now due
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
        // The window bounds how often a fault is written while the instance runs; on the way out there is no loop
        // left to bound, and holding anything back would simply lose it
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

        // The later occurrences are tallied all the same, they just wait for the window
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
        // The one case that needs two threads: the tally has to arrive between the batch being taken out and the
        // failed batch being put back
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

        // Both occurrences survive the failed write, and go out together once the repository can be written to
        TestResolvers.set(racing, "records",
            new RecordWriter(TestResolvers.factory(this.context.resourceResolver())));
        passTheWriteWindow();
        racing.flush();

        // Both subjects came through, which is what "nothing was lost" means here. The count is not asserted
        // exactly: the mock repository does not roll a failed commit back, so the abandoned attempts left their
        // own marks, which a real repository would have discarded
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

        // Once the repository can be written to again, the tally that could not be written goes out
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

        // Four faults in quick succession, but the writer gives up after the third failure: a repository that
        // cannot be written to at all must not turn every recorded error into a failed commit and a stack trace
        for (int i = 0; i < 4; i++) {
            failing.logError(new IllegalStateException(BOOM), ErrorContext.of("some.Thing", "attempt" + i));
            passTheWriteWindow();
        }
        assertEquals(ErrorLoggerImpl.FAILURES_BEFORE_PAUSE, counting.commits,
            "kept trying " + counting.commits + " times");

        // It does come back, though, so a repository that is fixed starts being written to again on its own
        this.now += ErrorLoggerImpl.PAUSE_MS;
        failing.logError(new IllegalStateException(BOOM), ErrorContext.of("some.Thing", "afterThePause"));

        assertEquals(ErrorLoggerImpl.FAILURES_BEFORE_PAUSE + 1, counting.commits);
    }

    @Test
    void aWriteThatFailsOutrightLeavesTheWriterRunning() throws ReflectiveOperationException
    {
        // Not a failed commit — those are handled — but the writing itself raising something. On a scheduled task
        // that escapes once, the tail of every later burst would go unwritten
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

        // The next recording is written as if nothing had happened, and takes the tally that could not be written
        // out with it: what the writer could not do it did not lose
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

        // Being unable to keep up must not be one more silent failure
        assertTrue(unreachable.getDroppedCount() > 0);
    }

    // ---------------------------------------------------------------- guards and lifecycle

    @Test
    void doesNotRecordAFailureRaisedWhileRecording() throws ReflectiveOperationException
    {
        // A commit hook failing on the very node being written would otherwise feed itself forever
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
        this.logger.logProblem(null, ErrorContext.EMPTY);
        this.logger.logProblem("   ", ErrorContext.EMPTY);

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
            // Stopping writes out whatever was still waiting, so nothing recorded on the way down is lost
            running.deactivate();
        }
        assertEquals(1, errors().size());

        ErrorLogger.logError(new IllegalStateException("after"));
        assertEquals(1, errors().size());
    }

    @Test
    void survivesAFailureItCannotEvenDescribe()
    {
        // Nothing about recording may raise a second failure, including asking the throwable about itself
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
    void givesUpWaitingForTheLastTalliesRatherThanHangingTheShutdown() throws ReflectiveOperationException
    {
        final ErrorLoggerImpl running = new ErrorLoggerImpl();
        TestResolvers.inject(running, this.context.resourceResolver());
        running.activate();
        TestResolvers.set(running, "shutdownWait", 0L);

        assertDoesNotThrow(running::deactivate);
    }

    @Test
    void stoppingWhileInterruptedLeavesTheThreadInterrupted() throws ReflectiveOperationException
    {
        final ErrorLoggerImpl running = new ErrorLoggerImpl();
        TestResolvers.inject(running, this.context.resourceResolver());
        running.activate();
        TestResolvers.set(running, "shutdownWait", 2_000L);
        // Hold the writer's one thread, so that stopping has to wait for it. Queuing a tally instead does not:
        // draining it takes microseconds, and an executor with nothing left to do returns from awaitTermination at
        // once without ever consulting the interrupt flag, leaving the interrupted path below unreached. The
        // assertion passed either way, since nothing clears the flag, so only this path's coverage noticed — and it
        // was a coin toss, failing this module's build on about every other run
        final CountDownLatch occupied = new CountDownLatch(1);
        final ScheduledExecutorService writer =
            (ScheduledExecutorService) TestResolvers.get(running, "ownWriter");
        writer.execute(() -> {
            try {
                occupied.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.currentThread().interrupt();

        try {
            running.deactivate();

            assertTrue(Thread.interrupted(), "the interrupt was swallowed rather than restored");
        } finally {
            // Let the thread go, whatever happened above: it is a daemon, but a test that leaves it parked for five
            // seconds slows down every one after it
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
     * @throws ReflectiveOperationException if the component's shape has changed
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
