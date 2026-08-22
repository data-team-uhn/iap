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
 * Default implementation of {@link ErrorLoggerService}, storing one node per distinct fault under
 * {@value ErrorLoggerService#LOGGED_ERRORS_PATH}.
 *
 * <p>
 * Recording does no repository work on the calling thread. The failure is fingerprinted, folded into an in-memory
 * tally and left there; a single background thread writes the accumulated tallies out shortly afterwards, prompted
 * both by the recording and by a timer of its own. That is a requirement rather than an optimization, for three
 * reasons, in descending order of severity:
 * </p>
 *
 * <ul>
 * <li>The callers most worth having are commit hooks, and a commit hook runs <em>inside</em> a commit. Oak's segment
 * store guards commits with a single non-reentrant permit, so a session opened and committed from within a commit
 * hook would block forever on a permit its own thread already holds, wedging every write in the instance.</li>
 * <li>A failing loop would otherwise cost one login and one commit per occurrence. Collapsing the copies bounds the
 * <em>storage</em>, not the writing; only tallying does that.</li>
 * <li>A single writer removes the read-modify-write race between threads recording the same fault at once, which
 * used to lose occurrences and could fail one of two threads creating the same node.</li>
 * </ul>
 *
 * <p>
 * The tally is bounded, and so is the rate at which any one fault is written. Neither is a retention policy: nothing
 * recorded is ever discarded, and the tally is keyed by fingerprint, so it is bounded by the same thing the stored
 * records are — the number of ways this build can fail. Should even that overflow, the count of what could not be
 * kept up with is reported rather than quietly forgotten.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, service = ErrorLoggerService.class)
public class ErrorLoggerImpl implements ErrorLoggerService
{
    /** How many distinct faults may be waiting to be written. A diagnostic must not be able to exhaust the heap. */
    static final int MAX_PENDING = 1000;

    /** How often the same fault is written. One failing in a tight loop costs one commit per window. */
    static final long WRITE_INTERVAL_MS = 60_000;

    /** How many consecutive failed writes before the writer stops trying for a while. */
    static final int FAILURES_BEFORE_PAUSE = 3;

    /** How long the writer waits after giving up, before trying again. */
    static final long PAUSE_MS = 300_000;

    /** How long a shutdown waits for the last tallies to be written. */
    static final long SHUTDOWN_WAIT_MS = 5_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(ErrorLoggerImpl.class);

    /**
     * The characters something may be made of to take part in a fingerprint. Anything else is a caller accidentally
     * passing something data-derived, which would mint a record per distinct value; the value is still recorded, it
     * just does not get to decide identity.
     */
    private static final String STABLE_CHARACTERS = "[A-Za-z0-9_.$ -]";

    /** How long a label chosen in code plausibly is. */
    private static final int MAX_LABEL_LENGTH = 64;

    /** What an operation may look like: a short label, such as {@code computeTags}. */
    private static final Pattern STABLE_LABEL = Pattern.compile(STABLE_CHARACTERS + "{1," + MAX_LABEL_LENGTH + "}");

    /**
     * What a component may look like. The same characters, but as long as a fully-qualified class name needs to be
     * rather than as long as a label: several classes in this build are already past sixty characters, and a
     * length limit there would drop the single most useful field of the record for exactly the classes whose names
     * say the most.
     */
    private static final Pattern STABLE_COMPONENT = Pattern.compile(STABLE_CHARACTERS + "{1,255}");

    /** What names a problem whose phrase has no stable part at all. */
    private static final String UNSTABLE_PROBLEM = "unspecified problem";

    /** The node type recording a fault something was thrown for. */
    private static final String EXCEPTION_TYPE = "err:LoggedFailure";

    /** The node type recording a fault nothing was thrown for. */
    private static final String PROBLEM_TYPE = "err:LoggedProblem";

    /**
     * Whether this thread is already recording. Anything that fails while a record is being written — a commit hook
     * throwing on the very node being written, most plausibly — is logged and dropped rather than tallied, which is
     * what stops a fault that touches error tracking from feeding itself forever.
     */
    private static final ThreadLocal<Boolean> RECORDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Reference
    private ResourceResolverFactory resolverFactory;

    /** The faults seen but not yet written, by fingerprint. */
    private final Map<String, PendingError> pending = new ConcurrentHashMap<>();

    /** When each fault was last written, by fingerprint, so that a fault in a loop is not written continuously. */
    private final Map<String, Long> lastWritten = new ConcurrentHashMap<>();

    /** How many tallies had to be dropped because the pending map was full. */
    private final AtomicLong dropped = new AtomicLong();

    /**
     * How many consecutive attempts to write have failed. Only ever touched by the writer, but an atomic all the
     * same: a test drives the writer from its own thread, and the single-threaded executor is an invariant worth
     * stating rather than relying on.
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** When the writer may try again, after having given up. Volatile for the same reason, and 64 bits wide. */
    private volatile long pausedUntil;

    /**
     * Whether a write is already queued. One task per burst rather than one per occurrence: the tally is bounded, but
     * the executor's queue is not, and a fault in a tight loop would otherwise pile up hundreds of thousands of
     * no-op tasks behind the one write they are all waiting for.
     */
    private final AtomicBoolean queued = new AtomicBoolean();

    /** Where the writing happens. Replaced in tests by one that runs on the calling thread. */
    private Executor writer;

    /** The executor to shut down on deactivation, {@code null} when the writer was supplied from outside. */
    private ScheduledExecutorService ownWriter;

    /** The clock. Replaced in tests, so that windows can be tested without waiting for them. */
    private LongSupplier clock = System::currentTimeMillis;

    /** How long stopping waits for the last tallies. A field so that a test need not actually wait that long. */
    private long shutdownWait = SHUTDOWN_WAIT_MS;

    /** How often the same fault is written, and how often the writer looks for work. A field for the same reason. */
    private long writeInterval = WRITE_INTERVAL_MS;

    /** Turns tallies into repository nodes. */
    private RecordWriter records;

    @Activate
    protected void activate()
    {
        // A single thread, so that every write is serialized and no two of them can race for the same node
        this.ownWriter = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "iap-error-tracking");
            thread.setDaemon(true);
            return thread;
        });
        this.writer = this.ownWriter;
        // A recording queues a write, but a fault written within the last window is deliberately held back, so the
        // last occurrences of a burst are due only once it is over — and nothing arrives to notice that. Without a
        // clock of its own, an instance that fails three times in a minute and then behaves would leave one
        // occurrence and a stale date in the repository for as long as it kept running
        this.ownWriter.scheduleWithFixedDelay(this::write, this.writeInterval, this.writeInterval,
            TimeUnit.MILLISECONDS);
        this.records = new RecordWriter(this.resolverFactory);
        ErrorLogger.setService(this);
    }

    @Deactivate
    protected void deactivate()
    {
        // Stop taking new tallies before writing out the ones in hand, so that the last flush is the last word
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
        logError(error, ErrorContext.EMPTY);
    }

    @Override
    public void logError(final Throwable error, final ErrorContext context)
    {
        if (error == null) {
            return;
        }
        final ErrorContext described = context == null ? ErrorContext.EMPTY : context;
        submit(error, () -> {
            final String stated = component(described.getComponent());
            final String component = stated != null ? stated : component(Fingerprint.inferComponent(error));
            final String operation = label(described.getOperation());
            tally(Fingerprint.of(error, component, operation),
                // Printed inside the supplier, so that only the first occurrence of a fault pays for it: printing a
                // deep cause chain builds up to 64 KB of string, and a fault in a tight loop reaches here per
                // occurrence
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
        final ErrorContext described = context == null ? ErrorContext.EMPTY : context;
        final String phrase = problem.strip();
        // What decides identity still has to be stable, but a phrase that is not must be recorded all the same: the
        // caller is reporting something wrong, and dropping it here would make the component whose whole purpose is
        // to catch silent failures the one committing them. So the stable head names the fault and the phrase itself
        // is kept as a message, exactly the way a throwable's message is left out of the identity but not the record
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
     * Runs the tallying, under the two guarantees this service owes its callers: it never throws, and it never
     * records a failure raised while recording a failure.
     *
     * @param subject what is being recorded, only ever used to say what could not be
     * @param tallying the tallying to do
     */
    private void submit(final Object subject, final Runnable tallying)
    {
        if (Boolean.TRUE.equals(RECORDING.get())) {
            LOGGER.error("Not recording {}, it was raised while recording another error", subject);
            return;
        }
        try {
            tallying.run();
            if (this.queued.compareAndSet(false, true)) {
                this.writer.execute(this::write);
            }
        } catch (final Throwable e) {
            // The caller is already handling a failure; recording it must not raise a second one. Throwable rather
            // than Exception because printing a deeply nested cause chain can run the stack out
            LOGGER.error("Could not record {}: {}", subject, e.getMessage(), e);
        }
    }

    /**
     * Folds one occurrence into the tally of its fault, starting one if this is the first time it has been seen.
     *
     * @param fingerprint what names the fault
     * @param start how to start tallying it, used only when this is the first occurrence
     * @param message the message of this particular occurrence, may be {@code null}
     * @param context what the caller said about this particular occurrence
     */
    private void tally(final String fingerprint, final Supplier<PendingError> start, final String message,
        final ErrorContext context)
    {
        final long now = this.clock.getAsLong();
        // Checked outside compute rather than inside it, since a ConcurrentHashMap may not be consulted from within
        // its own mapping function. Concurrent recorders can therefore overshoot the bound slightly, which is a far
        // better trade than serializing every failure site in the instance behind one lock
        if (this.pending.size() >= MAX_PENDING && !this.pending.containsKey(fingerprint)) {
            this.dropped.incrementAndGet();
            return;
        }
        // Tallying inside compute, and draining with remove, so that the two are serialized by the map itself: an
        // occurrence either joins the batch being written or starts the next tally, and cannot fall between them
        this.pending.compute(fingerprint, (key, existing) -> {
            final PendingError tallied = existing == null ? start.get() : existing;
            tallied.record(message, context, now);
            return tallied;
        });
    }

    /**
     * Writes out what is due, both when a recording asked for it and when the clock did, and keeps whatever that
     * raises off the writer thread — a scheduled task that throws is never run again, which would leave the tail of
     * every later burst unwritten.
     */
    private void write()
    {
        // Cleared before the writing rather than after, so that an occurrence tallied while a write is in progress
        // queues another one instead of being left to wait for the next tick
        this.queued.set(false);
        try {
            flush();
        } catch (final Throwable e) {
            LOGGER.error("Could not write the recorded errors: {}", e.getMessage(), e);
        }
    }

    /**
     * Writes out every tally there is, whether or not its window has passed. For the way out only: the window keeps a
     * fault failing in a loop from being written continuously, and on the way out there is no loop left to bound.
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
     * Writes out every tally that is due, in one commit. Runs on the writer thread only, so it is the single writer
     * the design relies on.
     */
    void flush()
    {
        flush(false);
    }

    /**
     * Writes out a batch of tallies, in one commit.
     *
     * @param everything whether to write the tallies whose window has not passed yet as well
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
        RECORDING.set(Boolean.TRUE);
        try {
            this.records.write(batch);
            batch.keySet().forEach(fingerprint -> this.lastWritten.put(fingerprint, now));
            this.consecutiveFailures.set(0);
        } catch (final Throwable e) {
            keep(batch);
            noteFailedWrite(e, now);
        } finally {
            RECORDING.remove();
        }
    }

    /**
     * Takes the tallies that are due out of the pending map. A fault written recently is left where it is: it will go
     * out with the next batch after its window, which is what keeps a fault failing in a loop from being written
     * continuously.
     *
     * @param now the current moment
     * @param everything whether to take the tallies whose window has not passed yet as well
     * @return the tallies to write, by fingerprint
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
        // Only faults nothing is waiting to write can be forgotten here, and forgetting one only means it may be
        // written a little sooner than its window would have allowed
        this.lastWritten.entrySet().removeIf(
            written -> now - written.getValue() >= WRITE_INTERVAL_MS && !this.pending.containsKey(written.getKey()));
        return batch;
    }

    /**
     * Puts a batch that could not be written back into the tally, folded under whatever has been recorded since, so
     * that a failure to write loses nothing but the moment it would have been written at.
     *
     * @param batch the tallies that could not be written
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
     * Notes that a write failed, and stops trying for a while once they keep failing. Without that, a repository
     * that cannot be written to at all — a wrong ACL, a missing container, a read-only store — would turn every
     * recorded error into a session, a failed commit and a stack trace in the log.
     *
     * @param failure what went wrong
     * @param now the current moment
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
     * Accepts a caller-supplied label only when it looks like something chosen in code. A label takes part in the
     * fingerprint, so one derived from content — a path, an identifier, a rendered value — would mint a record per
     * distinct value in a store that never deletes anything.
     *
     * @param value the label to check, may be {@code null}
     * @return the label, or {@code null} when there was none or it cannot be trusted to be stable
     */
    private static String label(final String value)
    {
        return stable(value, STABLE_LABEL);
    }

    /**
     * Accepts a caller-supplied or guessed component only when it looks like a class name.
     *
     * @param value the class name to check, may be {@code null}
     * @return the class name, or {@code null} when there was none or it cannot be trusted to be stable
     */
    private static String component(final String value)
    {
        return stable(value, STABLE_COMPONENT);
    }

    /**
     * Accepts a value only when it is made of nothing but what may decide identity.
     *
     * @param value the value to check, may be {@code null}
     * @param shape what a value of this kind may look like
     * @return the stripped value, or {@code null} when there was none or it cannot be trusted to be stable
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
     * The part of a reported phrase that may name a fault: its leading run of label characters. A caller reporting
     * {@code unknown comparator: 'sameDay'} is describing one fault rather than one per comparator, and stopping at
     * the first character a label may not contain is what turns such a phrase into the constant the fingerprint
     * needs. The phrase itself is kept as a message either way, so nothing the caller said is lost.
     *
     * @param phrase what the caller reported, stripped and not blank
     * @return a phrase stable enough to name the fault by
     */
    private static String stableHead(final String phrase)
    {
        // lookingAt rather than matches: the same shape a whole label has to have, but only asked of the beginning
        final Matcher head = STABLE_LABEL.matcher(phrase);
        if (!head.lookingAt()) {
            return UNSTABLE_PROBLEM;
        }
        final String named = head.group().strip();
        return named.chars().anyMatch(Character::isLetter) ? named : UNSTABLE_PROBLEM;
    }
}
