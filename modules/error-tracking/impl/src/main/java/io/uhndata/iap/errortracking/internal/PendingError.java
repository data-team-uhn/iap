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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.stream.Collectors;

import io.uhndata.iap.errortracking.api.ErrorContext;

/**
 * One fault, tallied in memory until it is written to the repository.
 *
 * <p>
 * Everything that identifies the fault — the component, the operation, and either the throwable's class and trace or
 * the problem's phrase — is fixed when the fault is first seen, since by construction every later occurrence shares
 * it. Everything that varies between occurrences is accumulated instead: the count, when it was last seen, and a
 * bounded sample of the messages, subjects and actors involved.
 * </p>
 *
 * <p>
 * The samples keep the most recent values rather than the first ones. The report they feed is a "what is going wrong
 * now" instrument, and a sample of the first twenty subjects would freeze on whatever happened to arrive in the first
 * minute of an outage, which is the least interesting window there is.
 * </p>
 *
 * <p>
 * Not thread-safe: instances are confined to the map guarding them, and are only ever read by the single thread that
 * writes them out.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class PendingError
{
    /** How many distinct paths a fault is remembered as having happened to. */
    static final int MAX_SUBJECTS = 20;

    /** How many distinct messages of one fault are remembered. */
    static final int MAX_MESSAGES = 5;

    /** How many distinct users a fault is remembered as having happened to. */
    static final int MAX_ACTORS = 10;

    /** The node type recording this fault, {@code err:LoggedFailure} or {@code err:LoggedProblem}. */
    private final String primaryType;

    /** Which code was running, a class name; {@code null} when nothing named it and nothing could be guessed. */
    private final String component;

    /** What that code was trying to do; {@code null} when the caller did not say. */
    private final String operation;

    /** The class of what was thrown, {@code null} for a problem nothing was thrown for. */
    private final String throwableType;

    /** What was found wrong, {@code null} for a failure something was thrown for. */
    private final String problem;

    /** The stack trace of the first occurrence seen, {@code null} for a problem. */
    private final String stackTrace;

    /** The distinct messages seen, oldest first. */
    private final SequencedSet<String> messages = new LinkedHashSet<>();

    /** The distinct paths the fault happened to, oldest first. */
    private final SequencedSet<String> subjects = new LinkedHashSet<>();

    /** The distinct users the fault happened to, oldest first. */
    private final SequencedSet<String> actors = new LinkedHashSet<>();

    /** How many occurrences have been tallied but not yet written. */
    private long occurrences;

    /** When the most recent of them happened. */
    private long lastSeen;

    /** Everything else the caller said about the most recent occurrence. */
    private String lastContext;

    /**
     * Starts tallying a fault.
     *
     * @param primaryType the node type recording it
     * @param component which code was running, may be {@code null}
     * @param operation what it was trying to do, may be {@code null}
     * @param throwableType the class of what was thrown, {@code null} for a problem
     * @param problem what was found wrong, {@code null} for a thrown failure
     * @param stackTrace the stack trace, {@code null} for a problem
     */
    PendingError(final String primaryType, final String component, final String operation,
        final String throwableType, final String problem, final String stackTrace)
    {
        this.primaryType = primaryType;
        this.component = component;
        this.operation = operation;
        this.throwableType = throwableType;
        this.problem = problem;
        this.stackTrace = stackTrace;
    }

    /**
     * Tallies one occurrence of this fault.
     *
     * @param message the message of this particular occurrence, may be {@code null}
     * @param context what the caller said about this particular occurrence, never {@code null}
     * @param moment when it happened, in milliseconds since the epoch
     */
    void record(final String message, final ErrorContext context, final long moment)
    {
        this.occurrences++;
        this.lastSeen = Math.max(this.lastSeen, moment);
        remember(this.messages, message, MAX_MESSAGES);
        remember(this.subjects, context.getSubject(), MAX_SUBJECTS);
        remember(this.actors, context.getActor(), MAX_ACTORS);
        this.lastContext = describe(context);
    }

    /**
     * Takes over an older tally of the same fault, so that a batch that could not be written is not lost but folded
     * back into whatever has accumulated since. This tally is the newer one, so its context and its samples win.
     *
     * @param older the tally to absorb, of the same fault
     */
    void absorb(final PendingError older)
    {
        this.occurrences += older.occurrences;
        this.lastSeen = Math.max(this.lastSeen, older.lastSeen);
        absorbSample(this.messages, older.messages, MAX_MESSAGES);
        absorbSample(this.subjects, older.subjects, MAX_SUBJECTS);
        absorbSample(this.actors, older.actors, MAX_ACTORS);
        if (this.lastContext == null) {
            this.lastContext = older.lastContext;
        }
    }

    /**
     * The node type recording this fault.
     *
     * @return a JCR node type name
     */
    String getPrimaryType()
    {
        return this.primaryType;
    }

    /**
     * Which code was running.
     *
     * @return a class name, or {@code null}
     */
    String getComponent()
    {
        return this.component;
    }

    /**
     * What that code was trying to do.
     *
     * @return a label, or {@code null}
     */
    String getOperation()
    {
        return this.operation;
    }

    /**
     * The class of what was thrown.
     *
     * @return a class name, or {@code null} for a problem
     */
    String getThrowableType()
    {
        return this.throwableType;
    }

    /**
     * What was found wrong.
     *
     * @return a phrase, or {@code null} for a thrown failure
     */
    String getProblem()
    {
        return this.problem;
    }

    /**
     * The stack trace of the first occurrence seen. An exemplar rather than the identity: every occurrence shares
     * these frames by construction, but may carry a different message.
     *
     * @return a multi-line string, or {@code null} for a problem
     */
    String getStackTrace()
    {
        return this.stackTrace;
    }

    /**
     * How many occurrences are waiting to be written.
     *
     * @return a count, at least one for a fault that has been recorded at all
     */
    long getOccurrences()
    {
        return this.occurrences;
    }

    /**
     * When the most recent occurrence happened.
     *
     * @return milliseconds since the epoch
     */
    long getLastSeen()
    {
        return this.lastSeen;
    }

    /**
     * Everything else the caller said about the most recent occurrence.
     *
     * @return one {@code key: value} per line, or {@code null} when the caller said nothing more
     */
    String getLastContext()
    {
        return this.lastContext;
    }

    /**
     * The distinct messages seen.
     *
     * @return the sample, most recent first, never {@code null}
     */
    List<String> getMessages()
    {
        return mostRecentFirst(this.messages);
    }

    /**
     * The distinct paths this fault happened to.
     *
     * @return the sample, most recent first, never {@code null}
     */
    List<String> getSubjects()
    {
        return mostRecentFirst(this.subjects);
    }

    /**
     * The distinct users this fault happened to.
     *
     * @return the sample, most recent first, never {@code null}
     */
    List<String> getActors()
    {
        return mostRecentFirst(this.actors);
    }

    /**
     * Adds a value to a bounded sample, keeping the most recent ones. A value seen again moves to the front rather
     * than being counted twice: the sample says what a fault has been seen to happen to, and repeating an entry
     * would only crowd out the others.
     *
     * @param into the sample to add to
     * @param value the value to remember, ignored when {@code null}
     * @param cap how many values the sample keeps
     */
    private static void remember(final SequencedSet<String> into, final String value, final int cap)
    {
        if (value == null) {
            return;
        }
        into.remove(value);
        into.addLast(value);
        while (into.size() > cap) {
            into.removeFirst();
        }
    }

    /**
     * Folds an older sample of the same fault underneath this one, so the newer values stay at the front.
     *
     * @param into the newer sample, oldest first
     * @param older the sample to fold in, oldest first
     * @param cap how many values the sample keeps
     */
    private static void absorbSample(final SequencedSet<String> into, final SequencedSet<String> older,
        final int cap)
    {
        final SequencedSet<String> merged = new LinkedHashSet<>(older);
        merged.addAll(into);
        into.clear();
        into.addAll(merged);
        while (into.size() > cap) {
            into.removeFirst();
        }
    }

    /**
     * Reverses a sample into reading order.
     *
     * @param sample the values, oldest first
     * @return the same values, most recent first
     */
    private static List<String> mostRecentFirst(final SequencedSet<String> sample)
    {
        return List.copyOf(sample.reversed());
    }

    /**
     * Renders a context's extra details for storage.
     *
     * @param context what the caller said, never {@code null}
     * @return one {@code key: value} per line, or {@code null} when there were no details
     */
    private static String describe(final ErrorContext context)
    {
        final Map<String, String> details = context.getDetails();
        return details.isEmpty() ? null
            : details.entrySet().stream().map(detail -> detail.getKey() + ": " + detail.getValue())
                .collect(Collectors.joining("\n"));
    }
}
