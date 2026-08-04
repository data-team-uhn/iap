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

import java.util.List;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.errortracking.api.ErrorContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link PendingError}, the in-memory tally of one fault.
 *
 * @version $Id$
 * @since 0.1.0
 */
class PendingErrorTest
{
    @Test
    void remembersWhatIdentifiesTheFault()
    {
        final PendingError tally = failure();

        assertEquals("err:LoggedFailure", tally.getPrimaryType());
        assertEquals("some.Thing", tally.getComponent());
        assertEquals("doIt", tally.getOperation());
        assertEquals("java.lang.IllegalStateException", tally.getThrowableType());
        assertEquals("the trace", tally.getStackTrace());
        assertNull(tally.getProblem());
    }

    @Test
    void countsOccurrencesAndKeepsTheLatestMoment()
    {
        final PendingError tally = failure();

        tally.record("boom", ErrorContext.EMPTY, 3000);
        tally.record("boom", ErrorContext.EMPTY, 1000);

        assertEquals(2, tally.getOccurrences());
        // Out-of-order moments must not move "last seen" backwards
        assertEquals(3000, tally.getLastSeen());
    }

    @Test
    void keepsTheMostRecentSamples()
    {
        final PendingError tally = failure();
        for (int i = 0; i < 25; i++) {
            tally.record("message " + i, ErrorContext.EMPTY.about("/path/" + i).actingFor("user" + i), i);
        }

        assertEquals(PendingError.MAX_SUBJECTS, tally.getSubjects().size());
        assertEquals(PendingError.MAX_MESSAGES, tally.getMessages().size());
        assertEquals(PendingError.MAX_ACTORS, tally.getActors().size());
        assertEquals("/path/24", tally.getSubjects().get(0));
        assertEquals("message 24", tally.getMessages().get(0));
        assertEquals("user24", tally.getActors().get(0));
    }

    @Test
    void aValueSeenAgainMovesToTheFrontRatherThanBeingKeptTwice()
    {
        final PendingError tally = failure();

        tally.record(null, ErrorContext.EMPTY.about("/first"), 1);
        tally.record(null, ErrorContext.EMPTY.about("/second"), 2);
        tally.record(null, ErrorContext.EMPTY.about("/first"), 3);

        assertEquals(List.of("/first", "/second"), tally.getSubjects());
    }

    @Test
    void remembersOnlyTheLatestCircumstances()
    {
        final PendingError tally = failure();

        tally.record(null, ErrorContext.EMPTY.with("attempt", 1), 1);
        tally.record(null, ErrorContext.EMPTY.with("attempt", 2).with("phase", "computedTags"), 2);

        assertEquals("attempt: 2\nphase: computedTags", tally.getLastContext());
    }

    @Test
    void saysNothingAboutCircumstancesNobodyDescribed()
    {
        final PendingError tally = failure();

        tally.record(null, ErrorContext.EMPTY, 1);

        assertNull(tally.getLastContext());
    }

    @Test
    void takesOverATallyThatCouldNotBeWritten()
    {
        // A batch that fails to write is folded back under whatever has been recorded since, so a failure to write
        // loses nothing but the moment it would have been written at
        final PendingError older = failure();
        older.record("older message", ErrorContext.EMPTY.about("/older").actingFor("bob"), 1000);
        older.record("older message", ErrorContext.EMPTY.about("/older"), 1500);
        final PendingError newer = failure();
        newer.record("newer message", ErrorContext.EMPTY.about("/newer").actingFor("alice"), 2000);

        newer.absorb(older);

        assertEquals(3, newer.getOccurrences());
        assertEquals(2000, newer.getLastSeen());
        assertEquals(List.of("/newer", "/older"), newer.getSubjects());
        assertEquals(List.of("newer message", "older message"), newer.getMessages());
        assertEquals(List.of("alice", "bob"), newer.getActors());
    }

    @Test
    void takingOverKeepsTheNewerCircumstances()
    {
        final PendingError older = failure();
        older.record(null, ErrorContext.EMPTY.with("attempt", 1), 1000);
        final PendingError newer = failure();
        newer.record(null, ErrorContext.EMPTY.with("attempt", 2), 2000);

        newer.absorb(older);

        assertEquals("attempt: 2", newer.getLastContext());
    }

    @Test
    void takingOverFallsBackToTheOlderCircumstances()
    {
        final PendingError older = failure();
        older.record(null, ErrorContext.EMPTY.with("attempt", 1), 1000);
        final PendingError newer = failure();
        newer.record(null, ErrorContext.EMPTY, 2000);

        newer.absorb(older);

        assertEquals("attempt: 1", newer.getLastContext());
    }

    @Test
    void takingOverKeepsTheSamplesBounded()
    {
        final PendingError older = failure();
        final PendingError newer = failure();
        for (int i = 0; i < 15; i++) {
            older.record(null, ErrorContext.EMPTY.about("/older/" + i), i);
            newer.record(null, ErrorContext.EMPTY.about("/newer/" + i), 100 + i);
        }

        newer.absorb(older);

        assertEquals(PendingError.MAX_SUBJECTS, newer.getSubjects().size());
        assertEquals("/newer/14", newer.getSubjects().get(0));
    }

    @Test
    void tallesAProblemWithNoTraceAtAll()
    {
        final PendingError tally = new PendingError("err:LoggedProblem", "some.Thing", "evaluate", null,
            "unknown comparator", null);

        tally.record(null, ErrorContext.EMPTY, 1000);

        assertEquals("unknown comparator", tally.getProblem());
        assertNull(tally.getStackTrace());
        assertNull(tally.getThrowableType());
        assertEquals(List.of(), tally.getMessages());
    }

    /**
     * A tally of one thrown fault.
     *
     * @return the tally, with nothing recorded into it yet
     */
    private static PendingError failure()
    {
        return new PendingError("err:LoggedFailure", "some.Thing", "doIt", "java.lang.IllegalStateException", null,
            "the trace");
    }
}
