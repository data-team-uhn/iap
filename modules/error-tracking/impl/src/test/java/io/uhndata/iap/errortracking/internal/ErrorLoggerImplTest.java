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

import java.util.Calendar;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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
        assertTrue(unreachable.getDropped() > 0);
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
        // Occupy the writer so that stopping actually has to wait for something
        TestResolvers.set(running, "shutdownWait", 2_000L);
        running.logError(new IllegalStateException(BOOM));
        Thread.currentThread().interrupt();

        running.deactivate();

        assertTrue(Thread.interrupted(), "the interrupt was swallowed rather than restored");
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
