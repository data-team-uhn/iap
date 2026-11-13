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

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReadingRuns}: how a stop reaches the thread that is reading a submission.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ReadingRunsTest
{
    private static final String SUBMISSION = "/Submissions/aa/bb/cc/proposal-1";

    private final ReadingRuns runs = new ReadingRuns();

    @AfterEach
    void clearInterrupt()
    {
        Thread.interrupted();
    }

    @Test
    void hasNothingToStopWhenNobodyIsReading()
    {
        assertFalse(this.runs.stop(SUBMISSION));
    }

    // Outside a model call the thread may be using the repository, and an interrupt can break that
    @Test
    void notesAStopOnAThreadBetweenModelCallsWithoutInterruptingIt()
    {
        this.runs.begin(SUBMISSION);

        assertTrue(this.runs.stop(SUBMISSION));
        assertFalse(Thread.currentThread().isInterrupted());
        assertThrows(ReadingStoppedException.class, ReadingRuns::enterModelCall, "the next model call is not made");
        assertTrue(this.runs.end());
    }

    @Test
    void interruptsAThreadInsideAModelCall()
    {
        this.runs.begin(SUBMISSION);
        ReadingRuns.enterModelCall();

        assertTrue(this.runs.stop(SUBMISSION));
        assertTrue(Thread.currentThread().isInterrupted());
        assertThrows(ReadingStoppedException.class, ReadingRuns::leaveModelCall);
        assertFalse(Thread.currentThread().isInterrupted(), "taken down before the thread goes back to work");
        assertTrue(this.runs.end());
    }

    @Test
    void letsAnUnstoppedModelCallEnd()
    {
        this.runs.begin(SUBMISSION);
        ReadingRuns.enterModelCall();

        ReadingRuns.leaveModelCall();
        assertFalse(this.runs.stop("/Submissions/another"));
        assertFalse(this.runs.end());
    }

    @Test
    void forgetsTheReadingWhenItEnds()
    {
        this.runs.begin(SUBMISSION);
        this.runs.setFiles(SUBMISSION, Set.of("/a/file"));

        this.runs.end();
        assertNull(this.runs.getFiles(SUBMISSION));
        assertFalse(this.runs.stop(SUBMISSION));
    }

    // A stop that came after the last model call must not stay set on a thread that has moved on
    @Test
    void leavingSaysWhetherAStopLandedAndClearsIt()
    {
        this.runs.begin(SUBMISSION);
        this.runs.stop(SUBMISSION);

        assertTrue(this.runs.end());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void leavingAnUnstoppedReadingSaysSo()
    {
        this.runs.begin(SUBMISSION);

        assertFalse(this.runs.end());
    }

    @Test
    void leavingAReadingNeverEnteredIsHarmless()
    {
        this.runs.end();

        assertFalse(this.runs.stop(SUBMISSION));
    }

    @Test
    void refusesToBeginOnAThreadAlreadyToldToStop()
    {
        this.runs.begin(SUBMISSION);
        this.runs.stop(SUBMISSION);

        assertThrows(ReadingStoppedException.class, () -> this.runs.begin(SUBMISSION));
        assertTrue(this.runs.end());
    }

    // Two threads on one submission each keep their own entry, so neither hides the other from a stop
    @Test
    void stopsEveryThreadReadingTheSubmission() throws Exception
    {
        final CountDownLatch begun = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        final AtomicBoolean otherSawTheStop = new AtomicBoolean();
        final Thread other = new Thread(() -> {
            this.runs.begin(SUBMISSION);
            this.runs.setFiles(SUBMISSION, Set.of("/other/file"));
            begun.countDown();
            try {
                stopped.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            otherSawTheStop.set(this.runs.end());
        });
        other.start();
        begun.await();
        this.runs.begin(SUBMISSION);
        this.runs.setFiles(SUBMISSION, Set.of("/this/file"));

        assertEquals(Set.of("/other/file", "/this/file"), this.runs.getFiles(SUBMISSION));
        assertTrue(this.runs.stop(SUBMISSION));
        stopped.countDown();
        other.join();

        assertTrue(otherSawTheStop.get());
        assertTrue(this.runs.end());
    }

    // A replaced component must still reach the readings the old one started
    @Test
    void findsAReadingANewInstanceDidNotStart()
    {
        this.runs.begin(SUBMISSION);

        assertTrue(new ReadingRuns().stop(SUBMISSION));
        assertTrue(this.runs.end());
    }

    // The files of another submission, or of a thread not reading, are not this reading's
    @Test
    void keepsOnlyTheFilesOfTheSubmissionThisThreadReads()
    {
        this.runs.setFiles(SUBMISSION, Set.of("/not/reading"));
        this.runs.begin(SUBMISSION);
        this.runs.setFiles("/Submissions/another", Set.of("/another/file"));

        assertNull(this.runs.getFiles(SUBMISSION));
        assertNull(this.runs.getFiles("/Submissions/another"));
        this.runs.end();
    }

    // A handler wraps the stop in its own exception, and the engine wraps that again
    @Test
    void recognizesAStopWrappedInOtherFailures()
    {
        assertTrue(ReadingRuns.isCausedByStop(
            new IOException("step", new IllegalStateException(new ReadingStoppedException()))));
        assertFalse(ReadingRuns.isCausedByStop(new IOException("the model is down")));
    }

    // An interrupt from elsewhere, such as the instance shutting down, is not the submitter's stop
    @Test
    void doesNotReadAnInterruptFromElsewhereAsAStop()
    {
        Thread.currentThread().interrupt();
        this.runs.begin(SUBMISSION);

        assertFalse(ReadingRuns.isCausedByStop(new IOException("closed")));
        assertFalse(this.runs.end());
    }
}
