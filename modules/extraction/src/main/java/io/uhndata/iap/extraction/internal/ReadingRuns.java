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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.service.component.annotations.Component;

/**
 * The thread that is reading a submission, so a stop can interrupt the model call it is blocked in.
 *
 * <p>A reading is entered from more than one place on the same thread — the job that claimed it, then each
 * reading step — so the registration is counted. The thread stays registered until the outermost one leaves,
 * which is what a stop in the middle of a step still finds.</p>
 *
 * <p>A stop is noted on the thread, and interrupts it only inside a model call. Anywhere else the thread may be
 * in the repository, which an interrupt can break, so the next model call or step sees the stop instead.</p>
 *
 * <p>Registering, leaving, calling and stopping share one lock, so a stop cannot interrupt a thread that has
 * already left the model call or the reading.</p>
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
            super(ExtractionStatus.STOPPED_MESSAGE);
        }
    }

    /** Shared by every instance, since a model call reaches it from where no instance is at hand. */
    private static final Object STATE_LOCK = new Object();

    /** The threads inside a model call, the only place a stop interrupts. */
    private static final Set<Thread> CALLING_THREADS = ConcurrentHashMap.newKeySet();

    /** The threads told to stop, until they leave the reading. */
    private static final Set<Thread> STOPPING_THREADS = ConcurrentHashMap.newKeySet();

    private final ConcurrentMap<String, Thread> running = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, AtomicInteger> depth = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Set<String>> files = new ConcurrentHashMap<>();

    /**
     * Note that this thread is reading a submission.
     *
     * @param submission the submission's path
     * @throws Stopped when this thread was already told to stop
     */
    void begin(final String submission) throws Stopped
    {
        synchronized (STATE_LOCK) {
            if (isStopRequested()) {
                throw new Stopped();
            }
            this.running.put(submission, Thread.currentThread());
            this.depth.computeIfAbsent(submission, key -> new AtomicInteger()).incrementAndGet();
        }
    }

    /**
     * Note that this thread has left one entry into the reading. The registration drops when the last one does.
     *
     * <p>The outermost leaving also takes down a stop that landed since the last model call. Checked under the
     * same lock as {@link #stop}, so no stop can land after it and stay set on a thread that has moved on.</p>
     *
     * @param submission the submission's path
     * @return {@code true} when this thread had been told to stop
     */
    boolean end(final String submission)
    {
        synchronized (STATE_LOCK) {
            final Thread current = Thread.currentThread();
            final boolean stopped = Thread.interrupted() | STOPPING_THREADS.contains(current);
            final AtomicInteger count = this.depth.get(submission);
            if (count != null && count.decrementAndGet() <= 0) {
                this.depth.remove(submission, count);
                this.running.remove(submission, current);
                this.files.remove(submission);
                STOPPING_THREADS.remove(current);
            }
            return stopped;
        }
    }

    /**
     * Tell the thread reading a submission to stop, if one is, interrupting it when it is inside a model call.
     *
     * @param submission the submission's path
     * @return {@code true} when a thread was reading it
     */
    boolean stop(final String submission)
    {
        synchronized (STATE_LOCK) {
            final Thread thread = this.running.get(submission);
            if (thread == null) {
                return false;
            }
            STOPPING_THREADS.add(thread);
            if (CALLING_THREADS.contains(thread)) {
                thread.interrupt();
            }
            return true;
        }
    }

    /**
     * Note that this thread is about to ask a model, where a stop may interrupt it.
     *
     * @throws Stopped when this thread was already told to stop
     */
    static void enterModelCall() throws Stopped
    {
        synchronized (STATE_LOCK) {
            if (isStopRequested()) {
                throw new Stopped();
            }
            CALLING_THREADS.add(Thread.currentThread());
        }
    }

    /**
     * Note that the model call of this thread is over. A stop that came during the call is reported, and its
     * interrupt taken down, so the thread goes back to the repository uninterrupted.
     *
     * @throws Stopped when this thread was told to stop during the call
     */
    static void leaveModelCall() throws Stopped
    {
        synchronized (STATE_LOCK) {
            CALLING_THREADS.remove(Thread.currentThread());
            if (isStopRequested()) {
                Thread.interrupted();
                throw new Stopped();
            }
        }
    }

    /** Whether this thread was told to stop, or interrupted. */
    private static boolean isStopRequested()
    {
        return Thread.currentThread().isInterrupted() || STOPPING_THREADS.contains(Thread.currentThread());
    }

    /**
     * Whether a failure is a reading that was stopped, rather than a model that could not be reached.
     *
     * @param failure the failure
     * @return {@code true} when the reading was stopped
     */
    static boolean isStopped(final Throwable failure)
    {
        // A handler wraps the stop in its own failure, and the engine wraps that again
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof Stopped) {
                return true;
            }
        }
        return isStopRequested();
    }

    /**
     * Note which files the step under way reads, so a stop knows whose text it cut off.
     *
     * @param submission the submission's path
     * @param files the paths of the files the step reads
     */
    void setFiles(final String submission, final Set<String> files)
    {
        this.files.put(submission, Set.copyOf(files));
    }

    /**
     * The files the step under way reads, when one said.
     *
     * @param submission the submission's path
     * @return the file paths, or {@code null} when no step has said
     */
    Set<String> getFiles(final String submission)
    {
        return this.files.get(submission);
    }
}
