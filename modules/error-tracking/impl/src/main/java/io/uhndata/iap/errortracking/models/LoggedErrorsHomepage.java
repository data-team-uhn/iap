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
 * The {@code /LoggedErrors} container, holding every error the instance has recorded.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = LoggedErrorsHomepage.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LoggedErrorsHomepage extends EntityHomepage
{
    /** The Sling resource type of the container holding the recorded errors. */
    public static final String RESOURCE_TYPE = "err/LoggedErrorsHomepage";

    /**
     * Every recorded error, most recently seen first. Reads the children directly, so it needs no query and
     * therefore no index; deduplication is what keeps that affordable, since the count grows with distinct faults
     * rather than with how often they happen.
     *
     * @return the recorded errors, possibly empty, never {@code null}
     */
    @NotNull
    public List<LoggedError> getErrors()
    {
        return this.getChildren(LoggedError.RESOURCE_TYPE, LoggedError.class).stream()
            .sorted(LoggedError.MOST_RECENT_FIRST).toList();
    }

    /**
     * The recorded errors nobody has dealt with yet, or that have happened again since somebody did.
     *
     * @return the errors needing attention, most recently seen first, never {@code null}
     */
    @NotNull
    public List<LoggedError> getUnacknowledgedErrors()
    {
        return this.getErrors().stream().filter(error -> !error.isAcknowledged()).toList();
    }

    /**
     * The recorded errors somebody has dealt with. Still kept, still listed, just no longer asking for attention.
     *
     * @return the errors already dealt with, most recently seen first, never {@code null}
     */
    @NotNull
    public List<LoggedError> getAcknowledgedErrors()
    {
        return this.getErrors().stream().filter(LoggedError::isAcknowledged).toList();
    }

    /**
     * One recorded error, by the fingerprint naming it.
     *
     * @param fingerprint the name of the node recording the error
     * @return the recorded error, or {@code null} when no such error was recorded
     */
    @Nullable
    public LoggedError getError(@NotNull final String fingerprint)
    {
        return this.getChild(fingerprint, LoggedError.class);
    }

    /**
     * How many times all the recorded errors have happened, in total.
     *
     * @return a count, zero when nothing has been recorded
     */
    public long getTotalOccurrences()
    {
        return this.getErrors().stream().mapToLong(LoggedError::getOccurrences).sum();
    }

    /**
     * Whether anything recorded here is asking for attention.
     *
     * <p>
     * Answered from this node's own aggregated tags, which the {@code unacknowledged} marker is copied up into at
     * commit time, so it costs one property read. Deliberately not a walk over the children, and deliberately not
     * {@code hasTag}, which for an aggregated marker visits the whole subtree — here, every error ever recorded.
     * </p>
     *
     * @return {@code true} when at least one recorded error has not been dealt with
     */
    public boolean hasUnacknowledgedErrors()
    {
        final String[] aggregated =
            this.get(TagProcessor.Phase.BOTTOM_UP.getPropertyName(), String[].class);
        return aggregated != null && List.of(aggregated).contains(LoggedError.UNACKNOWLEDGED);
    }
}
