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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.llm.LLMCallGate;

/**
 * One slot, held for the length of a call.
 *
 * <p>Fair, so callers are served in the order they arrived rather than at random: the wait is long enough
 * that an unlucky caller would otherwise sit behind newer ones until its timeout ran out.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = LLMCallGate.class, immediate = true)
public class CallGateImpl implements LLMCallGate
{
    private final Semaphore slot = new Semaphore(1, true);

    @Override
    public Permit acquire(final Duration maxWait) throws IOException
    {
        try {
            if (!this.slot.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("No LLM call slot came free in " + maxWait.toMillis()
                    + " ms; another call is already in flight");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for an LLM call slot", e);
        }
        return permitFor(this.slot);
    }

    /**
     * A permit that frees its slot once, however many times it is closed.
     *
     * <p>{@link Semaphore#release()} is not idempotent: called twice it hands out a permit nobody took, and the
     * gate is one wider from then on for the life of the instance. A permit is an {@link AutoCloseable} handed to
     * callers, so closing one twice is a mistake waiting to be made, and it is the kind that never shows up as a
     * failure - only as calls quietly running two at a time.
     *
     * @param slot the semaphore to free
     * @return the permit
     */
    private static Permit permitFor(final Semaphore slot)
    {
        final AtomicBoolean held = new AtomicBoolean(true);
        return () -> {
            if (held.compareAndSet(true, false)) {
                slot.release();
            }
        };
    }
}
