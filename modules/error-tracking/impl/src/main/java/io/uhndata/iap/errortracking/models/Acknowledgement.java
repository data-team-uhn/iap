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

import java.util.Comparator;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;

/**
 * One decision somebody took about one recorded error: that it is understood, that a fix is coming, that it is being
 * left alone.
 *
 * <p>
 * Decisions are appended, never replaced, so what was decided and by whom stays on the record — which is the point of
 * keeping errors around in the first place. Deciding again about the same error simply adds another one of these.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Acknowledgement.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Acknowledgement extends Content
{
    /** The Sling resource type of a triage decision. */
    public static final String RESOURCE_TYPE = "err/Acknowledgement";

    /**
     * Sorts decisions with the most recent first. By how much had happened when each was taken rather than by date:
     * the count is what the decision is measured against, and two decisions in the same second still order.
     */
    public static final Comparator<Acknowledgement> MOST_RECENT_FIRST =
        Comparator.comparingLong(Acknowledgement::getAcknowledgedOccurrences)
            .thenComparing(Acknowledgement::getCreated,
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed();

    /** What was decided. */
    @ValueMapValue
    private String resolution;

    /** Why. */
    @ValueMapValue
    private String note;

    /** How much had happened when it was decided. */
    @ValueMapValue
    private Long acknowledgedOccurrences;

    /**
     * What was decided: the name of a tag in the {@value LoggedError#TRIAGE_CATEGORY} category, such as
     * {@code known-issue}.
     *
     * @return a tag name, never {@code null} in a well-formed record
     */
    @NotNull
    public String getResolution()
    {
        return this.resolution == null ? "" : this.resolution;
    }

    /**
     * Why it was decided, in the acknowledger's own words.
     *
     * @return the note, or {@code null} when none was left
     */
    @Nullable
    public String getNote()
    {
        return this.note;
    }

    /**
     * What the error's occurrence count had reached when this decision was taken. An error counts as dealt with only
     * while it has not happened again since, so this is what makes a recurrence undo an acknowledgement without
     * anything having to watch the clock.
     *
     * @return a count, zero when the record does not say
     */
    public long getAcknowledgedOccurrences()
    {
        return this.acknowledgedOccurrences == null ? 0L : this.acknowledgedOccurrences;
    }
}
