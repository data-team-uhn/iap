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
package io.uhndata.iap.workflows.internal;

import java.util.concurrent.atomic.AtomicInteger;

import javax.jcr.InvalidItemStateException;

import org.apache.sling.api.resource.PersistenceException;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of what a lost race with another commit turns into, and how often it is run again.
 *
 * @version $Id$
 * @since 0.1.0
 */
class RepositoryFailuresTest
{
    @Test
    void runsAnAttemptAgainAfterItLostARace() throws Exception
    {
        final AtomicInteger attempts = new AtomicInteger();

        final String result = RepositoryFailures.retryingConflicts(2, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw conflict();
            }
            return "done";
        });

        assertEquals("done", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void givesUpAfterTheRetriesAreSpent()
    {
        final AtomicInteger attempts = new AtomicInteger();

        assertThrows(InvalidStateException.class, () -> RepositoryFailures.retryingConflicts(2, () -> {
            attempts.incrementAndGet();
            throw conflict();
        }));
        assertEquals(3, attempts.get());
    }

    @Test
    void doesNotRetryAnythingElse()
    {
        final AtomicInteger attempts = new AtomicInteger();
        final InvalidStateException early = new InvalidStateException("It has not run out of time yet");

        final WorkflowException thrown = assertThrows(InvalidStateException.class,
            () -> RepositoryFailures.retryingConflicts(2, () -> {
                attempts.incrementAndGet();
                throw early;
            }));
        assertSame(early, thrown);
        assertEquals(1, attempts.get());
    }

    @Test
    void recognizesALostRaceByItsCause()
    {
        assertTrue(RepositoryFailures.isConflict(conflict()));
        assertFalse(RepositoryFailures.isConflict(new PersistenceException("disk full")));
    }

    private static WorkflowException conflict()
    {
        return RepositoryFailures.translate(
            new PersistenceException("commit failed", new InvalidItemStateException("OakState0001")));
    }
}
