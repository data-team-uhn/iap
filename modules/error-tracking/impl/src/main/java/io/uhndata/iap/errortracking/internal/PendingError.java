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
 * What identifies the fault is set when the tally starts: the component, the operation, and either the throwable's
 * class and trace or the problem's name. What varies between occurrences accumulates: the count, the time of the
 * latest occurrence, and samples of the messages, subjects and actors. A sample keeps the most recent values.
 * </p>
 *
 * <p>
 * Not thread-safe. The pending map's {@code compute} and {@code remove} confine a tally to one thread at a time.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class PendingError
{
    /** How many distinct paths a sample keeps. */
    static final int MAX_SUBJECTS = 20;

    /** How many distinct messages a sample keeps. */
    static final int MAX_MESSAGES = 5;

    /** How many distinct usernames a sample keeps. */
    static final int MAX_ACTORS = 10;

    /** The node type that records this fault, {@code err:LoggedFailure} or {@code err:LoggedProblem}. */
    private final String primaryType;

    /** The fully qualified name of the class that was running; {@code null} if neither caller nor trace named one. */
    private final String component;

    /** The label of what that code was doing; {@code null} if the caller did not name one. */
    private final String operation;

    /** The class name of what was thrown; {@code null} for a problem. */
    private final String throwableType;

    /** The name of the problem; {@code null} for a thrown failure. */
    private final String problem;

    /** The stack trace of the occurrence that started the tally; {@code null} for a problem. */
    private final String stackTrace;

    /** The distinct messages seen, oldest first. */
    private final SequencedSet<String> messages = new LinkedHashSet<>();

    /** The distinct paths the fault happened to, oldest first. */
    private final SequencedSet<String> subjects = new LinkedHashSet<>();

    /** The distinct usernames the fault happened on behalf of, oldest first. */
    private final SequencedSet<String> actors = new LinkedHashSet<>();

    /** How many occurrences have been tallied. */
    private long occurrences;

    /** When the latest of them happened, in milliseconds since the epoch. */
    private long lastSeen;

    /** The details given with the latest occurrence, one {@code key: value} per line, or {@code null} if none. */
    private String lastContext;

    /**
     * Starts tallying a fault.
     *
     * @param primaryType the node type that records it, {@code err:LoggedFailure} or {@code err:LoggedProblem}
     * @param component the fully qualified name of the class that was running, or {@code null} if unknown
     * @param operation the label of what it was doing, or {@code null} if unknown
     * @param throwableType the class name of what was thrown, or {@code null} for a problem
     * @param problem the name of the problem, or {@code null} for a thrown failure
     * @param stackTrace the stack trace, or {@code null} for a problem
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
     * @param message the message of this occurrence, or {@code null} if it has none
     * @param context what the caller said about this occurrence, not {@code null}
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
     * Adds an older tally of the same fault to this one. This tally's details and sample values come first, and the
     * older details are kept only if this tally has none.
     *
     * @param older the older tally of the same fault
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
     * The node type that records this fault.
     *
     * @return {@code err:LoggedFailure} or {@code err:LoggedProblem}
     */
    String getPrimaryType()
    {
        return this.primaryType;
    }

    /**
     * Which code was running.
     *
     * @return a fully qualified class name, or {@code null} if neither the caller nor the trace named one
     */
    String getComponent()
    {
        return this.component;
    }

    /**
     * What that code was doing.
     *
     * @return a label chosen in code, or {@code null} if the caller did not name one
     */
    String getOperation()
    {
        return this.operation;
    }

    /**
     * The class of what was thrown.
     *
     * @return a fully qualified class name, or {@code null} for a problem
     */
    String getThrowableType()
    {
        return this.throwableType;
    }

    /**
     * The name of the problem.
     *
     * @return a phrase chosen in code, or {@code null} for a thrown failure
     */
    String getProblem()
    {
        return this.problem;
    }

    /**
     * The stack trace of the occurrence that started the tally. Later occurrences may carry other messages.
     *
     * @return the printed trace, or {@code null} for a problem
     */
    String getStackTrace()
    {
        return this.stackTrace;
    }

    /**
     * How many occurrences have been tallied.
     *
     * @return a count, at least one once an occurrence has been tallied
     */
    long getOccurrences()
    {
        return this.occurrences;
    }

    /**
     * When the latest occurrence happened.
     *
     * @return milliseconds since the epoch
     */
    long getLastSeen()
    {
        return this.lastSeen;
    }

    /**
     * The details given with the latest occurrence.
     *
     * @return one {@code key: value} per line, or {@code null} if that occurrence had no details
     */
    String getLastContext()
    {
        return this.lastContext;
    }

    /**
     * The distinct messages seen.
     *
     * @return the sample, most recent first; empty if no occurrence had a message
     */
    List<String> getMessages()
    {
        return mostRecentFirst(this.messages);
    }

    /**
     * The distinct paths this fault happened to.
     *
     * @return the sample, most recent first; empty if no caller named a subject
     */
    List<String> getSubjects()
    {
        return mostRecentFirst(this.subjects);
    }

    /**
     * The distinct users this fault happened on behalf of.
     *
     * @return the usernames, most recent first; empty if no caller named a user
     */
    List<String> getActors()
    {
        return mostRecentFirst(this.actors);
    }

    /**
     * Adds a value to a sample, and drops the oldest values beyond its cap. A value already there moves to the most
     * recent end.
     *
     * @param into the sample to add to, oldest first
     * @param value the value to add; ignored if {@code null}
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
     * Adds an older sample of the same fault beneath a newer one, and drops the oldest values beyond its cap.
     *
     * @param into the newer sample, oldest first
     * @param older the older sample, oldest first
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
     * Renders a context's details for storage.
     *
     * @param context what the caller said, not {@code null}
     * @return one {@code key: value} per line, or {@code null} if there are no details
     */
    private static String describe(final ErrorContext context)
    {
        final Map<String, String> details = context.getDetails();
        return details.isEmpty() ? null
            : details.entrySet().stream().map(detail -> detail.getKey() + ": " + detail.getValue())
                .collect(Collectors.joining("\n"));
    }
}
