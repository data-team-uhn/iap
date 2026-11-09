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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.osgi.service.component.annotations.Component;

/**
 * Tracks which thread is reading each submission, so a stop can interrupt the model call that thread waits on.
 *
 * <p>The same thread enters a reading more than once: first the job, then each reading step. So each thread's
 * entries are counted, and it stays registered until its outermost entry ends. That way a stop in the middle of a
 * step still finds it. Two threads reading one submission each keep their own entry, and a stop reaches both.</p>
 *
 * <p>A stop is noted on the thread, and interrupts it only inside a model call. Anywhere else the thread may be
 * in the repository, which an interrupt can break, so the next model call or step sees the stop instead. Only a
 * stop counts as one: an interrupt from elsewhere, such as the instance shutting down, does not.</p>
 *
 * <p>All of it is kept in one place, shared by every instance, under one lock. A model call reaches it with no
 * instance at hand, and a replaced component still finds the readings the old one started. A stop cannot
 * interrupt a thread that has already left the model call or the reading.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ReadingRuns.class)
public class ReadingRuns
{
    /**
     * Thrown when a reading is stopped, so a caller can tell a stop from a model that could not be reached.
     * Unchecked, because it must pass through the workflow engine, which only declares its own checked exception.
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

    private static final Object STATE_LOCK = new Object();

    /** The threads inside a model call, the only place a stop interrupts. */
    private static final Set<Thread> CALLING_THREADS = ConcurrentHashMap.newKeySet();

    /** The threads told to stop, until they leave the reading. */
    private static final Set<Thread> STOPPING_THREADS = ConcurrentHashMap.newKeySet();

    /** What each reading thread reads, guarded by {@link #STATE_LOCK}. */
    private static final Map<Thread, Reading> THREAD_READINGS = new HashMap<>();

    /**
     * One thread's reading: the submission, how many entries are open, and the files its step reads.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class Reading
    {
        private final String submission;

        private int depth;

        private Set<String> files;

        Reading(final String submission)
        {
            this.submission = submission;
        }
    }

    /**
     * Registers this thread as reading a submission.
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
            THREAD_READINGS.computeIfAbsent(Thread.currentThread(), thread -> new Reading(submission)).depth++;
        }
    }

    /**
     * Ends one entry of this thread into the reading. The thread is unregistered when its outermost entry ends.
     *
     * <p>The outermost end also clears a stop that came after the last model call. It runs under the same lock as
     * {@link #stop}, so no stop can arrive after it and stay set on a thread that has moved on.</p>
     *
     * @param submission the submission's path
     * @return {@code true} when this thread had been told to stop
     */
    boolean end(final String submission)
    {
        synchronized (STATE_LOCK) {
            final Thread current = Thread.currentThread();
            final boolean stopped = STOPPING_THREADS.contains(current);
            final Reading reading = THREAD_READINGS.get(current);
            if (reading != null && --reading.depth <= 0) {
                THREAD_READINGS.remove(current);
                STOPPING_THREADS.remove(current);
            }
            return stopped;
        }
    }

    /**
     * Tells every thread reading a submission to stop, and interrupts the ones inside a model call.
     *
     * @param submission the submission's path
     * @return {@code true} when a thread was reading it; {@code false} when none was
     */
    boolean stop(final String submission)
    {
        synchronized (STATE_LOCK) {
            final List<Thread> threads = THREAD_READINGS.entrySet().stream()
                .filter(entry -> entry.getValue().submission.equals(submission))
                .map(Map.Entry::getKey)
                .toList();
            threads.forEach(thread -> {
                STOPPING_THREADS.add(thread);
                if (CALLING_THREADS.contains(thread)) {
                    thread.interrupt();
                }
            });
            return !threads.isEmpty();
        }
    }

    /**
     * Registers this thread as calling a model, where a stop may interrupt it.
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
     * Marks this thread's model call as over. A stop that came during the call is thrown here and its interrupt
     * cleared, so the thread goes back to the repository uninterrupted.
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

    /** Whether this thread was told to stop. */
    private static boolean isStopRequested()
    {
        return STOPPING_THREADS.contains(Thread.currentThread());
    }

    /**
     * Checks whether a failure comes from a stopped reading, not from a model that could not be reached.
     *
     * @param failure the failure
     * @return {@code true} when a {@link Stopped} is in the cause chain, or this thread was told to stop
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
     * Records which files this thread's current step reads, so a stop knows whose parsed text it cut off.
     *
     * @param submission the submission's path
     * @param files the paths of the files the step reads
     */
    void setFiles(final String submission, final Set<String> files)
    {
        synchronized (STATE_LOCK) {
            final Reading reading = THREAD_READINGS.get(Thread.currentThread());
            if (reading != null && reading.submission.equals(submission)) {
                reading.files = Set.copyOf(files);
            }
        }
    }

    /**
     * Returns the files the current steps reading a submission read.
     *
     * @param submission the submission's path
     * @return the file paths, or {@code null} when no step reading it has set them
     */
    Set<String> getFiles(final String submission)
    {
        synchronized (STATE_LOCK) {
            final List<Set<String>> read = THREAD_READINGS.values().stream()
                .filter(reading -> reading.submission.equals(submission) && reading.files != null)
                .map(reading -> reading.files)
                .toList();
            return read.isEmpty() ? null
                : read.stream().flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
        }
    }
}
