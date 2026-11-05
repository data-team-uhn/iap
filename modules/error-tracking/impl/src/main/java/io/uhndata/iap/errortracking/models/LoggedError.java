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
 * One recorded error: a fault the instance could not deal with on its own.
 *
 * <p>
 * It is abstract, and has no {@code @Model} of its own. {@link LoggedFailure} and {@link LoggedProblem} each register
 * as an adapter for this class, so {@code resource.adaptTo(LoggedError.class)} answers with whichever of the two the
 * resource is.
 * </p>
 *
 * <p>
 * A recorded error describes a fault, not one occurrence of it. Its count and dates say how often and when it
 * happened, and its samples what it happened to.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class LoggedError extends Content
{
    /** The Sling resource type shared by every kind of recorded error. */
    public static final String RESOURCE_TYPE = "err/LoggedError";

    /** Sorts recorded errors most recently seen first. */
    public static final Comparator<LoggedError> MOST_RECENT_FIRST =
        Comparator.comparing(LoggedError::getLastOccurrence).reversed();

    /** The tag category of the triage tags. */
    public static final String TRIAGE_CATEGORY = "error-triage";

    /** The tag of an error nobody has acknowledged, or one that happened again since somebody did. */
    public static final String UNACKNOWLEDGED_TAG = "unacknowledged";

    /**
     * The tag of an acknowledged error. An acknowledged error carries it beside the tag naming the resolution, which
     * may be this same tag.
     */
    public static final String ACKNOWLEDGED_TAG = "acknowledged";

    /** The fully qualified name of the class that was running. */
    @ValueMapValue
    private String component;

    /** The label of what it was doing. */
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

    /** A sample of the usernames it happened on behalf of. */
    @ValueMapValue
    private String[] actors;

    /** The details given with the most recent occurrence that had any. */
    @ValueMapValue
    private String lastContext;

    /**
     * Which code was running when the fault happened. For a plugin, this is the plugin's class, not the framework that
     * called it.
     *
     * @return a fully qualified class name, or {@code null} if neither the caller nor the stack trace named one
     */
    @Nullable
    public String getComponent()
    {
        return this.component;
    }

    /**
     * What that code was trying to do.
     *
     * @return a short label chosen in code, or {@code null} if the caller did not name one
     */
    @Nullable
    public String getOperation()
    {
        return this.operation;
    }

    /**
     * What broke, in one line, for a list of faults.
     *
     * @return the class name of what was thrown, or the name of the problem; empty only in a malformed record
     */
    @NotNull
    public abstract String getSummary();

    /**
     * How many times this fault has been recorded.
     *
     * @return a count, at least 1 even if the record says less
     */
    public long getOccurrences()
    {
        return this.occurrences == null ? 1L : Math.max(1L, this.occurrences);
    }

    /**
     * When this fault was first recorded.
     *
     * @return the node's {@code jcr:created}, or the epoch if the record has no creation date
     */
    @NotNull
    public Calendar getFirstOccurrence()
    {
        final Calendar created = this.getCreated();
        return created == null ? epoch() : created;
    }

    /**
     * When this fault was last recorded.
     *
     * @return the date; when it was first recorded if the record does not say, or the epoch if it says neither, so
     *         that such a record sorts last
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
     * Up to five distinct messages this fault was seen with, most recent first. For a failure, these are the
     * throwable's messages. For a problem, they are the reported phrases that differ from its name.
     *
     * @return the messages; empty if none was recorded
     */
    @NotNull
    public List<String> getMessages()
    {
        return this.messages == null ? List.of() : List.of(this.messages);
    }

    /**
     * Up to twenty distinct paths this fault happened to, most recent first. Nothing counts the paths left out.
     *
     * @return the paths; empty if no caller named a subject
     */
    @NotNull
    public List<String> getSubjects()
    {
        return this.subjects == null ? List.of() : List.of(this.subjects);
    }

    /**
     * Up to ten distinct users this fault happened on behalf of, most recent first.
     *
     * @return the usernames; empty if no caller named a user, as outside a request
     */
    @NotNull
    public List<String> getActors()
    {
        return this.actors == null ? List.of() : List.of(this.actors);
    }

    /**
     * The details given with the most recent occurrence that had any, one {@code key: value} per line.
     *
     * @return the details, or {@code null} if no occurrence had any
     */
    @Nullable
    public String getLastContext()
    {
        return this.lastContext;
    }

    /**
     * The decisions taken about this fault, in the order of {@link Acknowledgement#MOST_RECENT_FIRST}.
     *
     * @return the decisions, most recent first; empty if nobody has taken one
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
     * @return the latest decision, or {@code null} if nobody has taken one
     */
    @Nullable
    public Acknowledgement getLatestAcknowledgement()
    {
        return this.getAcknowledgements().stream().findFirst().orElse(null);
    }

    /**
     * Whether somebody has acknowledged this fault, and it has not happened again since. It reads the tags computed
     * from this error's own content, so a query over the repository gives the same answer.
     *
     * @return {@code true} if the computed tags include {@value #ACKNOWLEDGED_TAG}; {@code false} if they do not, or
     *         if they have not been computed yet
     */
    public boolean isAcknowledged()
    {
        // Asked for positively: other processors add their own tags to the same property
        return triageMarkers().contains(ACKNOWLEDGED_TAG);
    }

    /**
     * The tags computed from this error's own content, the triage tags among them.
     *
     * @return the tag names; empty if none has been computed
     */
    @NotNull
    public List<String> getTriageMarkers()
    {
        return triageMarkers();
    }

    /**
     * Reads the tags computed from this error's own content.
     *
     * @return the tag names; empty if none has been computed
     */
    private List<String> triageMarkers()
    {
        final String[] computed = this.get(TagProcessor.Phase.LOCAL.getPropertyName(), String[].class);
        return computed == null ? List.of() : List.of(computed);
    }

    /**
     * The date for a record that has none.
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
