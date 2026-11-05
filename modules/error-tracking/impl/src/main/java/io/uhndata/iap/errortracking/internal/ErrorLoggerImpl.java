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
package io.uhndata.iap.errortracking.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;

/**
 * Records errors under {@value ErrorLoggerService#LOGGED_ERRORS_PATH}, one node per distinct fault.
 *
 * <p>
 * Recording does no repository work on the calling thread. The failure is fingerprinted and added to a tally in
 * memory. One writer thread writes the tallies out, when a recording asks it to and on a timer of its own. With one
 * writer, two threads recording the same fault cannot race to create or update its node.
 * </p>
 *
 * <p>
 * The tally holds at most {@value #MAX_PENDING} distinct faults, and each fault is written at most once per
 * {@value #WRITE_INTERVAL_MS} milliseconds. A recording that does not fit in the tally is dropped, and
 * {@link #getDroppedCount()} counts it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, service = ErrorLoggerService.class)
public class ErrorLoggerImpl implements ErrorLoggerService
{
    /** How many distinct faults may be waiting to be written. */
    static final int MAX_PENDING = 1000;

    /** How often, at most, one fault is written. A fault failing in a tight loop costs one commit per interval. */
    static final long WRITE_INTERVAL_MS = 60_000;

    /** How many writes in a row may fail before the writer pauses. */
    static final int FAILURES_BEFORE_PAUSE = 3;

    /** How long the writer pauses. */
    static final long PAUSE_MS = 300_000;

    /** How long a shutdown waits for the last tallies to be written. */
    static final long SHUTDOWN_WAIT_MS = 5_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(ErrorLoggerImpl.class);

    /**
     * The characters a component, an operation or the name of a problem may contain. A value with any other
     * character probably comes from content, and would create a record per distinct value. The failure is still
     * recorded without that value. For a thrown failure, the component is then taken from the stack trace.
     */
    private static final String STABLE_CHARACTERS = "[A-Za-z0-9_.$ -]";

    /** How long an operation or the name of a problem may be. */
    private static final int MAX_LABEL_LENGTH = 64;

    /** What an operation may look like, such as {@code computeTags}. */
    private static final Pattern STABLE_LABEL = Pattern.compile(STABLE_CHARACTERS + "{1," + MAX_LABEL_LENGTH + "}");

    /** What a component may look like: a fully qualified class name, which is often longer than a label. */
    private static final Pattern STABLE_COMPONENT = Pattern.compile(STABLE_CHARACTERS + "{1,255}");

    /** The name of a problem whose phrase does not start with a usable name. */
    private static final String UNSTABLE_PROBLEM = "unspecified problem";

    /** The node type recording a fault something was thrown for. */
    private static final String EXCEPTION_TYPE = "err:LoggedFailure";

    /** The node type recording a fault nothing was thrown for. */
    private static final String PROBLEM_TYPE = "err:LoggedProblem";

    /**
     * Whether this thread is writing records. A failure raised meanwhile, such as a commit hook throwing on a node
     * being written, is logged and not tallied, so that it cannot feed itself.
     */
    private static final ThreadLocal<Boolean> ALREADY_RECORDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Reference
    private ResourceResolverFactory resolverFactory;

    /** The faults seen but not yet written, by fingerprint. */
    private final Map<String, PendingError> pending = new ConcurrentHashMap<>();

    /** When each fault was last written, in milliseconds since the epoch, by fingerprint. */
    private final Map<String, Long> lastWritten = new ConcurrentHashMap<>();

    /** How many recordings were dropped because the tally was full. */
    private final AtomicLong dropped = new AtomicLong();

    /** How many writes in a row have failed. Atomic because tests run the writer on threads of their own. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** When the writer may try again after pausing, in milliseconds since the epoch. Volatile for the same reason. */
    private volatile long pausedUntil;

    /**
     * Whether a write is already queued. A burst of recordings queues one write, not one per occurrence, since the
     * executor's queue has no bound.
     */
    private final AtomicBoolean queued = new AtomicBoolean();

    /** Where the writing runs. A field so that a test can run it on the calling thread. */
    private Executor writer;

    /** The executor this component made and shuts down as it stops; {@code null} while it is not running. */
    private ScheduledExecutorService ownWriter;

    /** The clock, in milliseconds since the epoch. A field so that a test can move it. */
    private LongSupplier clock = System::currentTimeMillis;

    /** How long stopping waits for the last tallies to be written. A field so that a test can shorten it. */
    private long shutdownWait = SHUTDOWN_WAIT_MS;

    /** How often one fault may be written, and how often the writer looks for work. A field for the same reason. */
    private long writeInterval = WRITE_INTERVAL_MS;

    /** Writes the tallies into the repository. */
    private RecordWriter records;

    @Activate
    protected void activate()
    {
        // One thread, so that no two writes race for the same node
        this.ownWriter = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "iap-error-tracking");
            thread.setDaemon(true);
            return thread;
        });
        this.writer = this.ownWriter;
        // A fault written less than an interval ago is held back. The end of a burst therefore becomes due after the
        // burst is over, when no recording arrives to queue a write, and only this timer writes it out
        this.ownWriter.scheduleWithFixedDelay(this::write, this.writeInterval, this.writeInterval,
            TimeUnit.MILLISECONDS);
        this.records = new RecordWriter(this.resolverFactory);
        ErrorLogger.setService(this);
    }

    @Deactivate
    protected void deactivate()
    {
        // Stop taking recordings first, so that the last write holds everything recorded
        ErrorLogger.unsetService(this);
        if (this.ownWriter != null) {
            this.ownWriter.execute(this::drain);
            this.ownWriter.shutdown();
            try {
                if (!this.ownWriter.awaitTermination(this.shutdownWait, TimeUnit.MILLISECONDS)) {
                    LOGGER.warn("Gave up waiting for the last recorded errors to be written");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.ownWriter = null;
        }
    }

    @Override
    public void logError(final Throwable error)
    {
        logError(error, ErrorContext.NO_CONTEXT);
    }

    @Override
    public void logError(final Throwable error, final ErrorContext context)
    {
        if (error == null) {
            return;
        }
        final ErrorContext described = context == null ? ErrorContext.NO_CONTEXT : context;
        submit(error, () -> {
            final String stated = component(described.getComponent());
            final String component = stated != null ? stated : component(Fingerprint.inferComponent(error));
            final String operation = label(described.getOperation());
            tally(Fingerprint.of(error, component, operation),
                // Printed only for the occurrence that starts a tally, since a fault in a tight loop arrives here
                // for every occurrence
                () -> new PendingError(EXCEPTION_TYPE, component, operation, error.getClass().getName(), null,
                    Fingerprint.print(error)),
                error.getMessage(), described);
        });
    }

    @Override
    public void logProblem(final String problem, final ErrorContext context)
    {
        if (problem == null || problem.isBlank()) {
            return;
        }
        final ErrorContext described = context == null ? ErrorContext.NO_CONTEXT : context;
        final String phrase = problem.strip();
        // A phrase that quotes a value is still recorded. Its leading words name the fault, and the whole phrase is
        // kept as a message
        final String named = stableHead(phrase);
        submit(phrase, () -> {
            final String component = component(described.getComponent());
            final String operation = label(described.getOperation());
            tally(Fingerprint.ofProblem(named, component, operation),
                () -> new PendingError(PROBLEM_TYPE, component, operation, null, named, null),
                named.equals(phrase) ? null : phrase, described);
        });
    }

    /**
     * Runs the tallying, then queues a write. It never throws. A failure raised while this thread writes records is
     * logged, not tallied.
     *
     * @param subject the throwable or the phrase being recorded, named in the log if it cannot be recorded
     * @param tallying the tallying to do
     */
    private void submit(final Object subject, final Runnable tallying)
    {
        if (Boolean.TRUE.equals(ALREADY_RECORDING.get())) {
            LOGGER.error("Not recording {}, it was raised while recording another error", subject);
            return;
        }
        try {
            tallying.run();
            if (this.queued.compareAndSet(false, true)) {
                this.writer.execute(this::write);
            }
        } catch (final Throwable e) {
            // Throwable, since printing a deeply nested cause chain can overflow the stack
            LOGGER.error("Could not record {}: {}", subject, e.getMessage(), e);
        }
    }

    /**
     * Adds one occurrence to the tally of its fault, and starts that tally if there is none.
     *
     * @param fingerprint the fingerprint naming the fault
     * @param start makes the tally; called only if there is none yet
     * @param message the message of this occurrence, or {@code null} if it has none
     * @param context what the caller said about this occurrence
     */
    private void tally(final String fingerprint, final Supplier<PendingError> start, final String message,
        final ErrorContext context)
    {
        final long now = this.clock.getAsLong();
        // Concurrent recorders can overshoot this bound slightly
        if (this.pending.size() >= MAX_PENDING && !this.pending.containsKey(fingerprint)) {
            this.dropped.incrementAndGet();
            return;
        }
        // compute here and remove in due() are atomic per key: an occurrence either joins the batch being written or
        // starts the next tally
        this.pending.compute(fingerprint, (key, existing) -> {
            final PendingError tallied = existing == null ? start.get() : existing;
            tallied.record(message, context, now);
            return tallied;
        });
    }

    /**
     * Writes out the tallies that are due, and catches whatever the writing throws. A scheduled task that throws is
     * never run again.
     */
    private void write()
    {
        // Cleared first, so that an occurrence tallied during this write queues another one
        this.queued.set(false);
        try {
            flush();
        } catch (final Throwable e) {
            LOGGER.error("Could not write the recorded errors: {}", e.getMessage(), e);
        }
    }

    /**
     * Writes out every tally, due or not, as the component stops.
     */
    private void drain()
    {
        this.queued.set(false);
        try {
            flush(true);
        } catch (final Throwable e) {
            LOGGER.error("Could not write the last recorded errors: {}", e.getMessage(), e);
        }
    }

    /**
     * Writes out every tally that is due, in one commit. Does nothing while the writer is paused. Runs only on the
     * writer thread.
     */
    void flush()
    {
        flush(false);
    }

    /**
     * Writes out a batch of tallies, in one commit. Does nothing while the writer is paused.
     *
     * @param everything {@code true} to write the tallies that are not due yet as well
     */
    private void flush(final boolean everything)
    {
        final long now = this.clock.getAsLong();
        if (now < this.pausedUntil) {
            return;
        }
        final Map<String, PendingError> batch = due(now, everything);
        if (batch.isEmpty()) {
            return;
        }
        ALREADY_RECORDING.set(Boolean.TRUE);
        try {
            this.records.write(batch);
            batch.keySet().forEach(fingerprint -> this.lastWritten.put(fingerprint, now));
            this.consecutiveFailures.set(0);
        } catch (final Throwable e) {
            keep(batch);
            noteFailedWrite(e, now);
        } finally {
            ALREADY_RECORDING.remove();
        }
    }

    /**
     * Takes the tallies that are due out of the pending map. The tally of a fault written less than an interval ago
     * stays, and goes out with the first batch after that interval.
     *
     * @param now the current time, in milliseconds since the epoch
     * @param everything {@code true} to take the tallies that are not due yet as well
     * @return the tallies to write, by fingerprint; empty if none is due
     */
    private Map<String, PendingError> due(final long now, final boolean everything)
    {
        final Map<String, PendingError> batch = new HashMap<>();
        for (final String fingerprint : this.pending.keySet()) {
            final Long written = this.lastWritten.get(fingerprint);
            if (everything || written == null || now - written >= this.writeInterval) {
                final PendingError tally = this.pending.remove(fingerprint);
                if (tally != null) {
                    batch.put(fingerprint, tally);
                }
            }
        }
        // Forgets when a fault was written, once its interval has passed and none of its occurrences is waiting
        this.lastWritten.entrySet().removeIf(
            written -> now - written.getValue() >= WRITE_INTERVAL_MS && !this.pending.containsKey(written.getKey()));
        return batch;
    }

    /**
     * Puts a batch that could not be written back into the tally, under whatever has been recorded since.
     *
     * @param batch the tallies that could not be written, by fingerprint
     */
    private void keep(final Map<String, PendingError> batch)
    {
        for (final Map.Entry<String, PendingError> entry : batch.entrySet()) {
            this.pending.merge(entry.getKey(), entry.getValue(), (newer, older) -> {
                newer.absorb(older);
                return newer;
            });
        }
    }

    /**
     * Logs a failed write, and pauses the writer for {@value #PAUSE_MS} milliseconds once
     * {@value #FAILURES_BEFORE_PAUSE} writes in a row have failed.
     *
     * @param failure what the write threw
     * @param now the current time, in milliseconds since the epoch
     */
    private void noteFailedWrite(final Throwable failure, final long now)
    {
        final int failures = this.consecutiveFailures.incrementAndGet();
        if (failures < FAILURES_BEFORE_PAUSE) {
            LOGGER.error("Could not record errors: {}", failure.getMessage(), failure);
        } else {
            this.pausedUntil = now + PAUSE_MS;
            LOGGER.error("Could not record errors {} times in a row, not trying again for a while: {}",
                failures, failure.getMessage(), failure);
        }
    }

    @Override
    public long getDroppedCount()
    {
        return this.dropped.get();
    }

    /**
     * Accepts an operation only if it looks like a label chosen in code.
     *
     * @param value the operation the caller named, or {@code null}
     * @return the stripped label, or {@code null} if there was none or it does not look like a label
     */
    private static String label(final String value)
    {
        return stable(value, STABLE_LABEL);
    }

    /**
     * Accepts a component only if it looks like a class name.
     *
     * @param value the class name the caller named or the stack trace gave, or {@code null}
     * @return the stripped class name, or {@code null} if there was none or it does not look like a class name
     */
    private static String component(final String value)
    {
        return stable(value, STABLE_COMPONENT);
    }

    /**
     * Accepts a value only if it may take part in a fingerprint.
     *
     * @param value the value to check, or {@code null}
     * @param shape the pattern the whole stripped value must match
     * @return the stripped value, or {@code null} if it was {@code null}, blank, or did not match
     */
    private static String stable(final String value, final Pattern shape)
    {
        if (value == null || value.isBlank()) {
            return null;
        }
        final String trimmed = value.strip();
        return shape.matcher(trimmed).matches() ? trimmed : null;
    }

    /**
     * The part of a reported phrase that names the fault: its leading run of the characters a label may contain, up
     * to {@value #MAX_LABEL_LENGTH} of them. {@code unknown comparator: 'sameDay'} names the fault
     * {@code unknown comparator}.
     *
     * @param phrase the reported phrase, stripped and not blank
     * @return that leading run, stripped, or {@value #UNSTABLE_PROBLEM} if it is empty or has no letter
     */
    private static String stableHead(final String phrase)
    {
        final Matcher head = STABLE_LABEL.matcher(phrase);
        if (!head.lookingAt()) {
            return UNSTABLE_PROBLEM;
        }
        final String named = head.group().strip();
        return named.chars().anyMatch(Character::isLetter) ? named : UNSTABLE_PROBLEM;
    }
}
