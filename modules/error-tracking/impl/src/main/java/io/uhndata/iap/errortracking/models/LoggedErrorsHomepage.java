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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityHomepage;
import io.uhndata.iap.tags.spi.TagProcessor;

/**
 * The {@code /LoggedErrors} homepage, which holds every error the instance has recorded.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = LoggedErrorsHomepage.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LoggedErrorsHomepage extends EntityHomepage
{
    /** The Sling resource type of the homepage of the recorded errors. */
    public static final String RESOURCE_TYPE = "err/LoggedErrorsHomepage";

    /**
     * Every recorded error, most recently seen first. It reads the children directly, with no query.
     *
     * @return the recorded errors; empty if none was recorded
     */
    @NotNull
    public List<LoggedError> getErrors()
    {
        return this.getChildren(LoggedError.RESOURCE_TYPE, LoggedError.class).stream()
            .sorted(LoggedError.MOST_RECENT_FIRST).toList();
    }

    /**
     * The recorded errors nobody has acknowledged, or that happened again since somebody did.
     *
     * @return the errors needing attention, most recently seen first; empty if there are none
     */
    @NotNull
    public List<LoggedError> getUnacknowledgedErrors()
    {
        return this.getErrors().stream().filter(error -> !error.isAcknowledged()).toList();
    }

    /**
     * The recorded errors somebody has acknowledged, and that have not happened again since.
     *
     * @return the acknowledged errors, most recently seen first; empty if there are none
     */
    @NotNull
    public List<LoggedError> getAcknowledgedErrors()
    {
        return this.getErrors().stream().filter(LoggedError::isAcknowledged).toList();
    }

    /**
     * One recorded error, by its fingerprint.
     *
     * @param fingerprint the name of the node that records the error
     * @return the recorded error, or {@code null} if no error has that fingerprint
     */
    @Nullable
    public LoggedError getError(@NotNull final String fingerprint)
    {
        return this.getChild(fingerprint, LoggedError.class);
    }

    /**
     * How many times all the recorded errors happened, in total.
     *
     * @return the sum of their counts; zero if none was recorded
     */
    public long getTotalOccurrences()
    {
        return this.getErrors().stream().mapToLong(LoggedError::getOccurrences).sum();
    }

    /**
     * Whether any recorded error needs attention. The {@code unacknowledged} tag is aggregated, so the tags module
     * copies it into this node's {@code aggregatedTags}, and this is one property read. {@code hasTag} would visit
     * every recorded error.
     *
     * @return {@code true} if at least one recorded error needs attention
     */
    public boolean hasUnacknowledgedErrors()
    {
        final String[] aggregated =
            this.get(TagProcessor.Phase.BOTTOM_UP.getPropertyName(), String[].class);
        return aggregated != null && List.of(aggregated).contains(LoggedError.UNACKNOWLEDGED_TAG);
    }
}
