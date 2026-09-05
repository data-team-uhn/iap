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
 * How much of one item's history one person has seen. The node is named after their canonical user id.
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

    /** When they last looked. */
    @ValueMapValue
    private Calendar seenAt;

    /** The action they had seen up to. */
    @ValueMapValue
    private String seenAction;

    /** The newest snapshot they had seen. */
    @ValueMapValue
    private String seenSnapshot;

    /**
     * When this person last looked.
     *
     * <p>
     * This, and not a version, is what answers "has anything happened since?". Snapshots are taken only at the
     * milestones a process declares, so the newest thing to have happened to an item usually has no version of its own
     * — a marker naming a version could not see it. Compare this against the most recent {@link Action} naming the
     * item.
     * </p>
     *
     * @return a copy of when they looked, or {@code null} in a malformed record
     */
    @Nullable
    public Calendar getSeenAt()
    {
        // A copy, since Calendar is mutable and callers must not be able to alter the model's own state
        return this.seenAt == null ? null : (Calendar) this.seenAt.clone();
    }

    /**
     * The identifier of the action they had seen up to, when it is worth knowing more precisely than by time.
     *
     * @return an identifier, or {@code null} when only the time was recorded
     */
    @Nullable
    public String getSeenAction()
    {
        return this.seenAction;
    }

    /**
     * The newest snapshot this person had seen, which is where a "what changed since I looked" comparison has to start
     * from.
     *
     * <p>
     * Necessarily coarser than {@link #getSeenAt()}: there may well be no snapshot as recent as the moment they looked,
     * so a diff from here can show more than they had not seen.
     * </p>
     *
     * @return a version identifier, or {@code null} when they had seen no snapshot
     */
    @Nullable
    public String getSeenSnapshot()
    {
        return this.seenSnapshot;
    }

    /**
     * Whether something has happened to the item since this person looked.
     *
     * @param actionTime when the most recent action on the item happened
     * @return {@code true} if that action is newer than this marker, and {@code true} when the marker does not say when
     *         they looked — an unreadable marker must not claim they are up to date
     */
    public boolean isBehind(@NotNull final Calendar actionTime)
    {
        return this.seenAt == null || actionTime.after(this.seenAt);
    }
}
