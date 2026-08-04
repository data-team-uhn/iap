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
package io.uhndata.iap.errortracking.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a caller knows about a failure beyond the failure itself: which code was running, what it was trying to do,
 * what it was working on, and on whose behalf.
 *
 * <p>
 * The distinction that matters here is between the parts that identify the <em>fault</em> and the parts that identify
 * one <em>incident</em> of it. The {@link #getComponent() component} and the {@link #getOperation() operation} are
 * chosen in code, so they can safely take part in deciding whether two failures are the same one; the
 * {@link #getSubject() subject}, the {@link #getActor() actor} and the {@link #getDetails() details} vary with the
 * data flowing through the instance, so they are recorded as a sample of what a failure has been seen to happen to,
 * and never as part of its identity. That is what keeps the number of recorded errors bounded by how the instance is
 * built rather than by how much data it handles.
 * </p>
 *
 * <p>
 * Instances are immutable and are built by copying: {@code ErrorContext.of(Something.class, "doTheThing")
 * .about(path).with("attempt", 3)}. Every argument may be {@code null}, in which case it is quietly left out —
 * describing a failure must never itself need a null check at a site that is already failing.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ErrorContext
{
    /** A caller that knows nothing beyond the failure itself. */
    public static final ErrorContext EMPTY = new ErrorContext(null, null, null, null, Map.of());

    /**
     * How many details are kept. A detail is a hint for whoever reads the record, not a data feed, and one that
     * scrolls past a screen is no more useful than none.
     */
    private static final int MAX_DETAILS = 20;

    /** How long a single detail may be. Long enough for a path or a short identifier, short enough to read. */
    private static final int MAX_LENGTH = 500;

    /** Which code was running, a class name. */
    private final String component;

    /** What that code was trying to do, a label chosen in code. */
    private final String operation;

    /** The path of what was being worked on. */
    private final String subject;

    /** The user the work was being done for. */
    private final String actor;

    /** Anything else worth knowing, already rendered to text, in the order the caller supplied it. */
    private final Map<String, String> details;

    /**
     * Full constructor, private because instances are built by copying from {@link #EMPTY} or from a factory.
     *
     * @param component which code was running
     * @param operation what it was trying to do
     * @param subject the path of what it was working on
     * @param actor the user it was working for
     * @param details anything else worth knowing, already rendered and already bounded
     */
    private ErrorContext(final String component, final String operation, final String subject, final String actor,
        final Map<String, String> details)
    {
        this.component = component;
        this.operation = operation;
        this.subject = subject;
        this.actor = actor;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /**
     * A context naming the code that failed and what it was doing.
     *
     * @param component the class that was running, {@code null} to let it be inferred from the stack trace
     * @param operation what it was trying to do, a short label chosen in code such as {@code computeTags}; must not
     *            be derived from content, since it takes part in deciding whether two failures are the same one
     * @return a new context
     */
    @NotNull
    public static ErrorContext of(@Nullable final Class<?> component, @Nullable final String operation)
    {
        return of(component == null ? null : component.getName(), operation);
    }

    /**
     * A context naming the code that failed and what it was doing, for a caller that has the class name rather than
     * the class itself.
     *
     * @param component the name of the class that was running, {@code null} to let it be inferred from the stack
     *            trace
     * @param operation what it was trying to do, a short label chosen in code
     * @return a new context
     */
    @NotNull
    public static ErrorContext of(@Nullable final String component, @Nullable final String operation)
    {
        return new ErrorContext(trim(component), trim(operation), null, null, Map.of());
    }

    /**
     * A copy of this context that also names what was being worked on.
     *
     * @param subjectPath the path of the content the failure was about, ignored when {@code null} or blank
     * @return a new context, or this one when there is nothing to add
     */
    @NotNull
    public ErrorContext about(@Nullable final String subjectPath)
    {
        final String value = trim(subjectPath);
        return value == null ? this
            : new ErrorContext(this.component, this.operation, value, this.actor, this.details);
    }

    /**
     * A copy of this context that also names what was being worked on.
     *
     * @param subjectResource the content the failure was about, ignored when {@code null}
     * @return a new context, or this one when there is nothing to add
     */
    @NotNull
    public ErrorContext about(@Nullable final Resource subjectResource)
    {
        return subjectResource == null ? this : about(subjectResource.getPath());
    }

    /**
     * A copy of this context that also names who the work was being done for. Absent for anything running outside a
     * request: a commit hook, a scheduled task, an asynchronous listener.
     *
     * @param actorId the user the failing work was being done for, ignored when {@code null} or blank
     * @return a new context, or this one when there is nothing to add
     */
    @NotNull
    public ErrorContext actingFor(@Nullable final String actorId)
    {
        final String value = trim(actorId);
        return value == null ? this
            : new ErrorContext(this.component, this.operation, this.subject, value, this.details);
    }

    /**
     * A copy of this context carrying one more detail. The value is rendered to text immediately, in the caller's
     * own frame: recording happens later and on another thread, so nothing here may hold on to a caller's object,
     * and a half-built object at a failure site is exactly where {@code toString()} is likely to throw.
     *
     * @param key what the detail is called, ignored when {@code null} or blank
     * @param value the detail itself, ignored when {@code null}
     * @return a new context, or this one when there is nothing to add
     */
    @NotNull
    public ErrorContext with(@Nullable final String key, @Nullable final Object value)
    {
        final String name = trim(key);
        if (name == null || value == null || (this.details.size() >= MAX_DETAILS && !this.details.containsKey(name))) {
            return this;
        }
        final Map<String, String> extended = new LinkedHashMap<>(this.details);
        extended.put(name, render(value));
        return new ErrorContext(this.component, this.operation, this.subject, this.actor, extended);
    }

    /**
     * Which code was running when the failure happened. For a plugin, the plugin's own class rather than the
     * framework that called it, since that is what has to be fixed.
     *
     * @return a class name, or {@code null} when the caller left it to be inferred
     */
    @Nullable
    public String getComponent()
    {
        return this.component;
    }

    /**
     * What that code was trying to do.
     *
     * @return a short label, or {@code null} when the caller did not say
     */
    @Nullable
    public String getOperation()
    {
        return this.operation;
    }

    /**
     * The path of the content the failure was about.
     *
     * @return a path, or {@code null} when the caller did not say
     */
    @Nullable
    public String getSubject()
    {
        return this.subject;
    }

    /**
     * The user the failing work was being done for.
     *
     * @return a user id, or {@code null} when there was no user, which is the normal case outside a request
     */
    @Nullable
    public String getActor()
    {
        return this.actor;
    }

    /**
     * Everything else the caller thought worth knowing, already rendered to text.
     *
     * @return the details in the order they were added, a read-only map
     */
    @NotNull
    public Map<String, String> getDetails()
    {
        return this.details;
    }

    @Override
    public String toString()
    {
        return "ErrorContext[component=" + this.component + ", operation=" + this.operation
            + ", subject=" + this.subject + ", actor=" + this.actor + ", details=" + this.details + ']';
    }

    /**
     * Renders one detail, defending against the object that cannot describe itself. A failure site is exactly where
     * a half-built object is likely to be, so a throwing {@code toString()} is a real possibility and must not
     * become the second failure.
     *
     * @param value the detail to render, never {@code null}
     * @return the rendered value, truncated when very long, never {@code null}
     */
    private static String render(final Object value)
    {
        String rendered;
        try {
            rendered = String.valueOf(value);
        } catch (final RuntimeException e) {
            rendered = "<toString failed: " + e.getClass().getName() + '>';
        }
        return rendered.length() <= MAX_LENGTH ? rendered : rendered.substring(0, MAX_LENGTH) + "…";
    }

    /**
     * Normalizes a caller-supplied string, treating blank as absent so that callers need no null checks.
     *
     * @param value the string to normalize, may be {@code null}
     * @return the trimmed string, or {@code null} when there was nothing there
     */
    private static String trim(final String value)
    {
        if (value == null) {
            return null;
        }
        final String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
