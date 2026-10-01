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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
     * A gate with its one slot free. Shared with the client tests.
     *
     * @return the gate
     */
    static CallGateImpl gate()
    {
        return new CallGateImpl();
    }

    @Test
    void letsOneCallThroughAndNoMore() throws IOException
    {
        final CallGateImpl gate = gate();
        gate.acquire(BRIEFLY);

        final IOException refused = assertThrows(IOException.class, () -> gate.acquire(BRIEFLY));

        assertTrue(refused.getMessage().contains("another call is already in flight"), refused.getMessage());
    }

    @Test
    void freesTheSlotWhenThePermitIsClosed() throws IOException
    {
        final CallGateImpl gate = gate();
        final LLMCallGate.Permit permit = gate.acquire(BRIEFLY);

        permit.close();

        assertNotNull(gate.acquire(BRIEFLY));
    }

    // Closing twice must not hand out a permit nobody took: the gate would be one wider from then on, and
    // nothing would show it but calls quietly running two at a time
    @Test
    void freesTheSlotOnlyOnceHoweverOftenThePermitIsClosed() throws IOException
    {
        final CallGateImpl gate = gate();
        final LLMCallGate.Permit permit = gate.acquire(BRIEFLY);

        permit.close();
        permit.close();

        gate.acquire(BRIEFLY);
        final IOException refused = assertThrows(IOException.class, () -> gate.acquire(BRIEFLY));

        assertTrue(refused.getMessage().contains("another call is already in flight"), refused.getMessage());
    }

    // Two gates are two instances, so one holding its slot says nothing about the other
    @Test
    void holdsItsOwnSlotOnly() throws IOException
    {
        gate().acquire(BRIEFLY);

        assertNotNull(gate().acquire(BRIEFLY));
    }

    @Test
    void failsWhenInterruptedWhileWaiting() throws IOException
    {
        final CallGateImpl gate = gate();
        gate.acquire(BRIEFLY);
        Thread.currentThread().interrupt();

        final IOException failure = assertThrows(IOException.class, () -> gate.acquire(Duration.ofSeconds(5)));

        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
        assertTrue(failure.getMessage().startsWith("Interrupted"), failure.getMessage());
    }
}
