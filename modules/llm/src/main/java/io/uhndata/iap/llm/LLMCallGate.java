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
package io.uhndata.iap.llm;

import java.io.IOException;
import java.time.Duration;

/**
 * Caps how many model calls this instance has in flight at once, across every caller.
 *
 * <p>The provider does not refuse extra requests, it queues them, so every call gets slower. Waiting here
 * keeps the number the provider sees at what it handles well. The cap is the {@code maxCallsInFlight}
 * configuration of the LLM module.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface LLMCallGate
{
    /**
     * How many calls may be in flight at once.
     *
     * @return the cap, at least 1
     */
    int getMaxCallsInFlight();

    /**
     * Take a slot, waiting for one to free up.
     *
     * @param maxWait how long to wait for a slot before giving up
     * @return the slot; closing it frees it
     * @throws IOException if no slot came free in time, or the wait was interrupted
     */
    Permit acquire(Duration maxWait) throws IOException;

    /**
     * One slot. Close it when the call is over.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    interface Permit extends AutoCloseable
    {
        @Override
        void close();
    }
}
