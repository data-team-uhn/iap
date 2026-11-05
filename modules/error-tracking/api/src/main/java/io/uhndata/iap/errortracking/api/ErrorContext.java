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
 * What a caller knows about a failure besides the failure itself: which code was running, what it was doing, what it
 * was working on, and for whom.
 *
 * <p>
 * The {@link #getComponent() component} and the {@link #getOperation() operation} are chosen in code, and they decide
 * whether two failures are the same fault. The {@link #getSubject() subject}, the {@link #getActor() actor} and the
 * {@link #getDetails() details} vary with the data. They are kept only as a sample of what the fault happened to.
 * The number of recorded errors then grows with the ways the instance can fail, not with the data it handles.
 * </p>
 *
 * <p>
 * A context is immutable, and each method returns a copy:
 * {@code ErrorContext.of(Something.class, "doTheThing").about(path).with("attempt", 3)}. Every argument may be
 * {@code null}, and a {@code null} is left out, so a failure site needs no null checks.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ErrorContext
{
    /** A context that says nothing beyond the failure itself. */
    public static final ErrorContext NO_CONTEXT = new ErrorContext(null, null, null, null, Map.of());

    /** How many details a context keeps. */
    private static final int MAX_DETAILS = 20;

    /** How many characters of a detail are kept. */
    private static final int MAX_LENGTH = 500;

    /** The fully qualified name of the class that was running. */
    private final String component;

    /** What that code was doing, a label chosen in code. */
    private final String operation;

    /** The path of the content being worked on. */
    private final String subject;

    /** The username of the user the work was done for. */
    private final String actor;

    /** The other details, as text, in the order they were added. */
    private final Map<String, String> details;

    /**
     * Builds a context. Callers start from {@link #NO_CONTEXT} or from {@code of}, and add to it by copying.
     *
     * @param component the fully qualified name of the class that was running, or {@code null}
     * @param operation a label chosen in code, or {@code null}
     * @param subject the path of the content being worked on, or {@code null}
     * @param actor the username of the user the work was done for, or {@code null}
     * @param details the other details, already rendered as text and already bounded
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
     * @param component the class that was running; {@code null} to leave it out, and for a thrown failure the class
     *            is then taken from the stack trace
     * @param operation a short label chosen in code, such as {@code computeTags}, or {@code null} to leave it out. It
     *            decides whether two failures are the same fault, so it must never come from content
     * @return a new context
     */
    @NotNull
    public static ErrorContext of(@Nullable final Class<?> component, @Nullable final String operation)
    {
        return of(component == null ? null : component.getName(), operation);
    }

    /**
     * A context naming the code that failed, by class name, and what it was doing.
     *
     * @param component the fully qualified name of the class that was running; {@code null} to leave it out, and for
     *            a thrown failure the class is then taken from the stack trace
     * @param operation a short label chosen in code, such as {@code computeTags}, or {@code null} to leave it out
     * @return a new context
     */
    @NotNull
    public static ErrorContext of(@Nullable final String component, @Nullable final String operation)
    {
        return new ErrorContext(trim(component), trim(operation), null, null, Map.of());
    }

    /**
     * A copy of this context that names the content being worked on.
     *
     * @param subjectPath the path of that content; ignored if {@code null} or blank
     * @return a new context, or this one if there is nothing to add
     */
    @NotNull
    public ErrorContext about(@Nullable final String subjectPath)
    {
        final String value = trim(subjectPath);
        return value == null ? this
            : new ErrorContext(this.component, this.operation, value, this.actor, this.details);
    }

    /**
     * A copy of this context that names the content being worked on, by its path.
     *
     * @param subjectResource that content; ignored if {@code null}
     * @return a new context, or this one if there is nothing to add
     */
    @NotNull
    public ErrorContext about(@Nullable final Resource subjectResource)
    {
        return subjectResource == null ? this : about(subjectResource.getPath());
    }

    /**
     * A copy of this context that names the user the work was done for. Work outside a request, such as a commit hook
     * or a scheduled task, has no such user.
     *
     * @param actorId the username of that user; ignored if {@code null} or blank
     * @return a new context, or this one if there is nothing to add
     */
    @NotNull
    public ErrorContext actingFor(@Nullable final String actorId)
    {
        final String value = trim(actorId);
        return value == null ? this
            : new ErrorContext(this.component, this.operation, this.subject, value, this.details);
    }

    /**
     * A copy of this context with one more detail. The value is rendered to text at once, because the recording
     * happens later, on another thread. A context keeps up to 20 details, of up to 500 characters each.
     *
     * @param key the name of the detail, such as {@code attempt}; ignored if {@code null} or blank
     * @param value the detail, rendered with {@code String.valueOf}; ignored if {@code null}
     * @return a new context; this one if there is nothing to add, or if it already holds 20 details under other names
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
     * Which code was running when the failure happened. For a plugin, this is the plugin's class, not the framework
     * that called it.
     *
     * @return a fully qualified class name, or {@code null} if the caller did not name one
     */
    @Nullable
    public String getComponent()
    {
        return this.component;
    }

    /**
     * What that code was doing.
     *
     * @return a short label chosen in code, or {@code null} if the caller did not name one
     */
    @Nullable
    public String getOperation()
    {
        return this.operation;
    }

    /**
     * The content being worked on.
     *
     * @return a path, or {@code null} if the caller did not name one
     */
    @Nullable
    public String getSubject()
    {
        return this.subject;
    }

    /**
     * The user the work was done for.
     *
     * @return a username, or {@code null} if the caller did not name one, as outside a request
     */
    @Nullable
    public String getActor()
    {
        return this.actor;
    }

    /**
     * The other details the caller gave.
     *
     * @return a read-only map from each detail's name to its text, in the order they were added; empty if there are
     *         none
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
     * Renders one detail as text. A value whose {@code toString()} throws is rendered as
     * {@code <toString failed: the exception's class name>}, since a half-built object at a failure site may throw.
     *
     * @param value the detail to render, not {@code null}
     * @return the text, cut after {@link #MAX_LENGTH} characters and then ending in an ellipsis
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
     * Strips a caller's string, and treats a blank one as absent.
     *
     * @param value the string to strip, or {@code null}
     * @return the stripped string, or {@code null} if it was {@code null} or blank
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
