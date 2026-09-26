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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.service.component.annotations.Component;

/**
 * The thread that is reading a submission, so a stop can interrupt the model call it is blocked in.
 *
 * <p>A reading is entered from more than one place on the same thread — the job that claimed it, then each
 * intake step — so the registration is counted. The thread stays registered until the outermost one leaves,
 * which is what a stop in the middle of a step still finds.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ReadingRuns.class)
public class ReadingRuns
{
    /**
     * What an interrupted reading throws, so a caller can tell a stop from a model that could not be reached.
     * Unchecked: it has to cross the workflow engine, which only declares its own checked failure.
     *
     * @since 0.1.0
     */
    static final class Stopped extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        Stopped()
        {
            super(ExtractionStatus.STOPPED);
        }
    }

    private final ConcurrentMap<String, Thread> running = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, AtomicInteger> depth = new ConcurrentHashMap<>();

    /**
     * Note that this thread is reading a submission.
     *
     * @param submission the submission's path
     * @throws Stopped when this thread was already told to stop
     */
    void begin(final String submission) throws Stopped
    {
        if (Thread.currentThread().isInterrupted()) {
            throw new Stopped();
        }
        this.running.put(submission, Thread.currentThread());
        this.depth.computeIfAbsent(submission, key -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * Note that this thread has left one entry into the reading. The registration drops when the last one does.
     *
     * @param submission the submission's path
     */
    void end(final String submission)
    {
        final AtomicInteger count = this.depth.get(submission);
        if (count != null && count.decrementAndGet() <= 0) {
            this.depth.remove(submission, count);
            this.running.remove(submission, Thread.currentThread());
        }
    }

    /**
     * Interrupt the thread reading a submission, if one is.
     *
     * @param submission the submission's path
     * @return {@code true} when a thread was interrupted
     */
    boolean stop(final String submission)
    {
        final Thread thread = this.running.get(submission);
        if (thread == null) {
            return false;
        }
        thread.interrupt();
        return true;
    }

    /**
     * Whether a failure is a reading that was stopped, rather than a model that could not be reached.
     *
     * @param failure the failure
     * @return {@code true} when the reading was stopped
     */
    static boolean isStopped(final Throwable failure)
    {
        return failure instanceof Stopped || Thread.currentThread().isInterrupted();
    }
}
