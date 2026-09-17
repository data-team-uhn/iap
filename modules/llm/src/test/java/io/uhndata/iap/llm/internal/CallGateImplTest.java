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
package io.uhndata.iap.llm.internal;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.llm.LLMCallGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CallGateImpl}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class CallGateImplTest
{
    private static final Duration BRIEFLY = Duration.ofMillis(50);

    /**
     * A gate configured for this many calls at once. Shared with the client tests.
     *
     * @param maxCallsInFlight the cap
     * @return the activated gate
     */
    static CallGateImpl gate(final int maxCallsInFlight)
    {
        final CallGateImpl gate = new CallGateImpl();
        configure(gate, maxCallsInFlight);
        return gate;
    }

    private static void configure(final CallGateImpl gate, final int maxCallsInFlight)
    {
        final CallGateConfiguration config = mock(CallGateConfiguration.class);
        when(config.maxCallsInFlight()).thenReturn(maxCallsInFlight);
        gate.activate(config);
    }

    @Test
    void reportsTheConfiguredCap()
    {
        assertEquals(4, gate(4).getMaxCallsInFlight());
    }

    @Test
    void readsAnythingBelowOneAsOne()
    {
        assertEquals(1, gate(0).getMaxCallsInFlight());
        assertEquals(1, gate(-3).getMaxCallsInFlight());
    }

    @Test
    void letsAsManyCallsThroughAsConfiguredAndNoMore() throws IOException
    {
        final CallGateImpl gate = gate(2);
        gate.acquire(BRIEFLY);
        gate.acquire(BRIEFLY);

        final IOException refused = assertThrows(IOException.class, () -> gate.acquire(BRIEFLY));

        assertTrue(refused.getMessage().contains("2 calls are already in flight"), refused.getMessage());
    }

    @Test
    void freesTheSlotWhenThePermitIsClosed() throws IOException
    {
        final CallGateImpl gate = gate(1);
        final LLMCallGate.Permit permit = gate.acquire(BRIEFLY);

        permit.close();

        assertNotNull(gate.acquire(BRIEFLY));
    }

    // A slot taken before the cap changed goes back where it came from, so a change never ends up with more
    // slots than configured
    @Test
    void givesASlotBackToTheGateItCameFrom() throws IOException
    {
        final CallGateImpl gate = gate(1);
        final LLMCallGate.Permit old = gate.acquire(BRIEFLY);
        configure(gate, 1);
        gate.acquire(BRIEFLY);

        old.close();

        assertThrows(IOException.class, () -> gate.acquire(BRIEFLY));
    }

    @Test
    void failsWhenInterruptedWhileWaiting() throws IOException
    {
        final CallGateImpl gate = gate(1);
        gate.acquire(BRIEFLY);
        Thread.currentThread().interrupt();

        final IOException failure = assertThrows(IOException.class, () -> gate.acquire(Duration.ofSeconds(5)));

        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
        assertTrue(failure.getMessage().startsWith("Interrupted"), failure.getMessage());
    }
}
