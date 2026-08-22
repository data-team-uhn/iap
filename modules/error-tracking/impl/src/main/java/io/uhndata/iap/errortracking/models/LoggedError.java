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
package io.uhndata.iap.errortracking.models;

import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.tags.spi.TagProcessor;

/**
 * One recorded error: a fault the instance ran into and could not deal with on its own.
 *
 * <p>
 * Abstract, and deliberately without a {@code @Model} annotation of its own: a recording is either a
 * {@link LoggedFailure} or a {@link LoggedProblem}, and each of those registers itself as an adapter for this type,
 * so that {@code resource.adaptTo(LoggedError.class)} yields whichever one is really there.
 * </p>
 *
 * <p>
 * What is stored here identifies the fault, not any one occurrence of it. The counts and dates say how much of it
 * there has been, and the samples say what it has been seen to happen to — a sample, never an exhaustive list.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class LoggedError extends Content
{
    /** The Sling resource type shared by every kind of recorded error. */
    public static final String RESOURCE_TYPE = "err/LoggedError";

    /** Sorts recorded errors most recently seen first, which is the order somebody reading them wants. */
    public static final Comparator<LoggedError> MOST_RECENT_FIRST =
        Comparator.comparing(LoggedError::getLastOccurrence).reversed();

    /** The tag category the triage markers belong to. */
    public static final String TRIAGE_CATEGORY = "error-triage";

    /** The tag marking an error nobody has dealt with yet, or one that has happened again since somebody did. */
    public static final String UNACKNOWLEDGED = "unacknowledged";

    /**
     * The tag marking an error somebody has dealt with. Always present on an acknowledged error, alongside the tag
     * naming what was decided — which may be this same one, since a plain {@code acknowledged} is a decision too.
     */
    public static final String ACKNOWLEDGED = "acknowledged";

    /** Which code was running. */
    @ValueMapValue
    private String component;

    /** What it was trying to do. */
    @ValueMapValue
    private String operation;

    /** How many times this fault has been recorded. */
    @ValueMapValue
    private Long occurrences;

    /** When it was last recorded. */
    @ValueMapValue
    private Calendar lastOccurrence;

    /** A sample of the messages it was seen with. */
    @ValueMapValue
    private String[] messages;

    /** A sample of the paths it happened to. */
    @ValueMapValue
    private String[] subjects;

    /** A sample of the users it happened on behalf of. */
    @ValueMapValue
    private String[] actors;

    /** Everything else known about the most recent occurrence. */
    @ValueMapValue
    private String lastContext;

    /**
     * Which code was running when the fault happened. For a plugin, the plugin's own class rather than the framework
     * that called it, since that is what has to be fixed.
     *
     * @return a class name, or {@code null} when nothing could be established
     */
    @Nullable
    public String getComponent()
    {
        return this.component;
    }

    /**
     * What that code was trying to do.
     *
     * @return a short label chosen in code, or {@code null} when the caller did not say
     */
    @Nullable
    public String getOperation()
    {
        return this.operation;
    }

    /**
     * A one-line description of what broke, for a reader skimming a list of faults.
     *
     * @return the class of what was thrown, or what was found wrong, never {@code null}
     */
    @NotNull
    public abstract String getSummary();

    /**
     * How many times this fault has been recorded. At least one: a fault recorded by a session that could not count
     * it is worth one occurrence rather than none.
     *
     * @return a count, at least 1
     */
    public long getOccurrences()
    {
        return this.occurrences == null ? 1L : Math.max(1L, this.occurrences);
    }

    /**
     * When this fault was first recorded.
     *
     * @return a date, never {@code null}
     */
    @NotNull
    public Calendar getFirstOccurrence()
    {
        final Calendar created = this.getCreated();
        return created == null ? epoch() : created;
    }

    /**
     * When this fault was last recorded. Falls back to when it was first seen, and then to the epoch, so that a
     * record written by something that could not date it sorts last rather than breaking the whole listing.
     *
     * @return a date, never {@code null}
     */
    @NotNull
    public Calendar getLastOccurrence()
    {
        if (this.lastOccurrence != null) {
            return (Calendar) this.lastOccurrence.clone();
        }
        final Calendar created = this.getCreated();
        return created == null ? epoch() : created;
    }

    /**
     * A sample of the distinct messages this fault was seen with, most recent first. Several, because what varies
     * between occurrences is deliberately outside the fault's identity: the same broken code reporting two different
     * paths is one fault seen twice, not two faults. For a thrown failure these are the throwable's messages; for a
     * problem, the phrases the caller reported that were too variable to name the fault by.
     *
     * @return the sampled messages, possibly empty, never {@code null}
     */
    @NotNull
    public List<String> getMessages()
    {
        return this.messages == null ? List.of() : List.of(this.messages);
    }

    /**
     * A sample of the paths this fault happened to, most recent first. Only ever a sample, and deliberately without
     * a count of what it left out: content that has to be found and repaired must carry its own marker, the way a
     * node whose tags could not be computed does.
     *
     * @return the sampled paths, possibly empty, never {@code null}
     */
    @NotNull
    public List<String> getSubjects()
    {
        return this.subjects == null ? List.of() : List.of(this.subjects);
    }

    /**
     * A sample of the users this fault happened on behalf of, most recent first. Empty for anything running outside
     * a request.
     *
     * @return the sampled users, possibly empty, never {@code null}
     */
    @NotNull
    public List<String> getActors()
    {
        return this.actors == null ? List.of() : List.of(this.actors);
    }

    /**
     * Everything else known about the most recent occurrence, one {@code key: value} per line.
     *
     * @return the details, or {@code null} when nothing more was said
     */
    @Nullable
    public String getLastContext()
    {
        return this.lastContext;
    }

    /**
     * The decisions somebody has taken about this fault, most recent first. Appended rather than replaced, so the
     * whole history of what was decided about it is here.
     *
     * @return the decisions, most recent first, possibly empty, never {@code null}
     */
    @NotNull
    public List<Acknowledgement> getAcknowledgements()
    {
        return this.getChildren(Acknowledgement.RESOURCE_TYPE, Acknowledgement.class).stream()
            .sorted(Acknowledgement.MOST_RECENT_FIRST).toList();
    }

    /**
     * The most recent decision taken about this fault.
     *
     * @return the latest decision, or {@code null} when nobody has taken one
     */
    @Nullable
    public Acknowledgement getLatestAcknowledgement()
    {
        return this.getAcknowledgements().stream().findFirst().orElse(null);
    }

    /**
     * Whether somebody has dealt with this fault and it has not happened again since. Read from the triage tags
     * rather than recomputed here, so that the answer is the same one a query over the repository would give.
     *
     * @return {@code true} when this error needs no further attention for now
     */
    public boolean isAcknowledged()
    {
        // Read from the property the computing phase owns rather than from the effective tags: these markers are
        // always derived from the decisions below, never placed by hand, so this is both the precise question and
        // one property read. Tested for positively, because that property is the union of what every processor of
        // that phase contributed, not only the triage one: asking whether anything other than `unacknowledged` is
        // there would read as acknowledged the moment some unrelated processor tagged an error for its own reasons.
        // An error whose markers have not been computed yet reads as needing attention, which is by far the better
        // way round to be wrong
        return triageMarkers().contains(ACKNOWLEDGED);
    }

    /**
     * The triage markers computed for this error.
     *
     * @return the markers, possibly empty, never {@code null}
     */
    @NotNull
    public List<String> getTriageMarkers()
    {
        return triageMarkers();
    }

    /**
     * Reads the property the computing phase stores its results in.
     *
     * @return the markers, possibly empty, never {@code null}
     */
    private List<String> triageMarkers()
    {
        final String[] computed = this.get(TagProcessor.Phase.LOCAL.getPropertyName(), String[].class);
        return computed == null ? List.of() : List.of(computed);
    }

    /**
     * The start of time, for a record with no usable date at all.
     *
     * @return the epoch
     */
    private static Calendar epoch()
    {
        final Calendar start = Calendar.getInstance();
        start.setTimeInMillis(0);
        return start;
    }
}
