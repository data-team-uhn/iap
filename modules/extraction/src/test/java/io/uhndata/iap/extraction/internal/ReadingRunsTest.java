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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReadingRuns}.
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

    @Test
    void interruptsTheThreadThatIsReading()
    {
        this.runs.begin(SUBMISSION);

        assertTrue(this.runs.stop(SUBMISSION));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    // The job and each step enter the same reading on one thread; only the outermost leaving ends it
    @Test
    void staysRegisteredUntilTheOutermostEntryLeaves()
    {
        this.runs.begin(SUBMISSION);
        this.runs.begin(SUBMISSION);
        this.runs.setFiles(SUBMISSION, Set.of("/a/file"));

        this.runs.end(SUBMISSION);
        assertEquals(Set.of("/a/file"), this.runs.getFiles(SUBMISSION));

        this.runs.end(SUBMISSION);
        assertNull(this.runs.getFiles(SUBMISSION));
        assertFalse(this.runs.stop(SUBMISSION));
    }

    @Test
    void leavingAReadingNeverEnteredIsHarmless()
    {
        this.runs.end(SUBMISSION);

        assertFalse(this.runs.stop(SUBMISSION));
    }

    @Test
    void refusesToBeginOnAThreadAlreadyToldToStop()
    {
        Thread.currentThread().interrupt();

        assertThrows(ReadingRuns.Stopped.class, () -> this.runs.begin(SUBMISSION));
    }

    // A handler wraps the stop in a failure of its own, and the engine wraps that again
    @Test
    void recognizesAStopWrappedInOtherFailures()
    {
        assertTrue(ReadingRuns.isStopped(
            new IOException("step", new IllegalStateException(new ReadingRuns.Stopped()))));
        assertFalse(ReadingRuns.isStopped(new IOException("the model is down")));
    }

    @Test
    void readsAnInterruptedThreadAsStopped()
    {
        Thread.currentThread().interrupt();

        assertTrue(ReadingRuns.isStopped(new IOException("closed")));
    }
}
