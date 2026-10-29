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
package io.uhndata.iap.history.models;

import java.util.Calendar;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;

/**
 * How recently one person looked at one piece of content, and what they saw then. The marker is a child of the
 * content's {@link LastSeen} container, named after the person's canonical username.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Marker.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Marker extends Content
{
    /** The Sling resource type of one viewer's marker. */
    public static final String RESOURCE_TYPE = "hist/Marker";

    @ValueMapValue
    private Calendar seenAt;

    @ValueMapValue
    private String seenAction;

    @ValueMapValue
    private String seenSnapshot;

    /**
     * When this person last looked at the content.
     *
     * <p>
     * This, and not a JCR version, is what answers "has anything happened since?". Versions are taken only at the
     * milestones a workflow declares, so the newest change to the content usually has no version of its own. Compare
     * this against when the most recent {@link Action} naming the content happened.
     * </p>
     *
     * @return a copy of that time, or {@code null} in a malformed record
     */
    @Nullable
    public Calendar getSeenAt()
    {
        return this.seenAt == null ? null : (Calendar) this.seenAt.clone();
    }

    /**
     * The newest action they had seen, for when two actions happened too close together for the time to tell apart.
     *
     * @return the path of that {@code hist:Action}, or {@code null} if only the time was recorded
     */
    @Nullable
    public String getSeenAction()
    {
        return this.seenAction;
    }

    /**
     * The newest JCR version of the content this person had seen, which is where a "what changed since I looked"
     * comparison starts.
     *
     * <p>
     * Coarser than {@link #getSeenAt()}. There may be no version as recent as the moment they looked, so a comparison
     * from here can show changes they had already seen.
     * </p>
     *
     * @return the {@code jcr:uuid} of that version, or {@code null} if the content had no version when they looked
     */
    @Nullable
    public String getSeenSnapshot()
    {
        return this.seenSnapshot;
    }

    /**
     * Whether the content has changed since this person looked.
     *
     * @param actionTime when the most recent action naming the content happened, as {@link Action#getOccurredAt()}
     *            gives it
     * @return {@code true} if that action happened after they looked, or if the marker does not say when they looked
     */
    public boolean isBehind(@NotNull final Calendar actionTime)
    {
        return this.seenAt == null || actionTime.after(this.seenAt);
    }
}
