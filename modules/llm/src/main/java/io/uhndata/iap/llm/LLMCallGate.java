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
 * Holds every model call in this instance to one at a time, across every caller.
 *
 * <p>The provider serves a proposal-sized call on its own, whatever else is asked of it at the same time:
 * measured on Qwen3.8-27B at 200k tokens, a wave of K calls took K x 38s however large K was. It does not
 * refuse the extra requests, it queues them, so sending more than one only parks calls in that queue where
 * they spend their timeout waiting. Waiting here instead keeps that wait where the caller can see it.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface LLMCallGate
{
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
