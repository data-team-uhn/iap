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
 * One decision somebody took about a recorded error, such as that it is a known issue. A new decision is added beside
 * the earlier ones, which stay.
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
     * Sorts decisions most recent first: by the error's count when each was taken, then by when each was created. A
     * count orders two decisions taken in the same second.
     */
    public static final Comparator<Acknowledgement> MOST_RECENT_FIRST =
        Comparator.comparingLong(Acknowledgement::getAcknowledgedOccurrences)
            .thenComparing(Acknowledgement::getCreated,
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed();

    /** The name of the triage tag decided on. */
    @ValueMapValue
    private String resolution;

    /** Why, in the words of whoever decided. */
    @ValueMapValue
    private String note;

    /** The error's count when the decision was taken. */
    @ValueMapValue
    private Long acknowledgedOccurrences;

    /**
     * What was decided: the name of a tag in the {@value LoggedError#TRIAGE_CATEGORY} category, such as
     * {@code known-issue}.
     *
     * @return a tag name; empty only in a malformed record
     */
    @NotNull
    public String getResolution()
    {
        return this.resolution == null ? "" : this.resolution;
    }

    /**
     * Why it was decided, in the words of whoever decided.
     *
     * @return the note, or {@code null} if none was left
     */
    @Nullable
    public String getNote()
    {
        return this.note;
    }

    /**
     * The error's occurrence count when this decision was taken. Once the error's count passes it, the error needs
     * attention again.
     *
     * @return a count; zero if the record does not say
     */
    public long getAcknowledgedOccurrences()
    {
        return this.acknowledgedOccurrences == null ? 0L : this.acknowledgedOccurrences;
    }
}
