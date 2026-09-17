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

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

import io.uhndata.iap.llm.LLMCallGate;

/**
 * A fair semaphore sized from {@link CallGateConfiguration}.
 *
 * <p>Changing the configuration swaps in a new semaphore. A slot taken from the old one is given back to the
 * old one, so the swap never loses or invents a slot.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = LLMCallGate.class, immediate = true)
@Designate(ocd = CallGateConfiguration.class)
public class CallGateImpl implements LLMCallGate
{
    private volatile Semaphore slots;

    private volatile int maxCallsInFlight;

    /**
     * Size the gate from the configuration. Also called when the configuration changes.
     *
     * @param config the configuration
     */
    @Activate
    @Modified
    protected void activate(final CallGateConfiguration config)
    {
        this.maxCallsInFlight = Math.max(1, config.maxCallsInFlight());
        this.slots = new Semaphore(this.maxCallsInFlight, true);
    }

    @Override
    public int getMaxCallsInFlight()
    {
        return this.maxCallsInFlight;
    }

    @Override
    public Permit acquire(final Duration maxWait) throws IOException
    {
        final Semaphore current = this.slots;
        try {
            if (!current.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("No LLM call slot came free in " + maxWait.toMillis() + " ms; "
                    + this.maxCallsInFlight + " calls are already in flight");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for an LLM call slot", e);
        }
        return current::release;
    }
}
