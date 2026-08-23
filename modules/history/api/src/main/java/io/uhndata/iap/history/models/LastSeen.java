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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;

/**
 * The markers on one piece of content: who has looked at it, and how recently.
 *
 * <p>
 * Filed under the content rather than under the person, on the cardinalities. A busy reviewer accumulates a marker for
 * every item they ever open, while an item is only ever looked at by a handful of people, and the lookup that actually
 * happens — what has this person seen of this item — is a direct path either way. So what is left to decide by is which
 * parent grows without bound. Nothing enumerates one person's markers: a dashboard starts from what is assigned to them
 * and asks about each item.
 * </p>
 *
 * <p>
 * The container is {@code IGNORE} on-parent-version, so opening a page never changes what a snapshot of it contains.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = LastSeen.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LastSeen extends Content
{
    /** The Sling resource type of the marker container. */
    public static final String RESOURCE_TYPE = "hist/LastSeen";

    /** The name of the container, as autocreated by the {@code hist:Watchable} mixin. */
    public static final String NODE_NAME = "hist:lastSeen";

    /**
     * Everybody who has looked at this content.
     *
     * @return the markers, possibly empty, never {@code null}
     */
    @NotNull
    public List<Marker> getMarkers()
    {
        return this.getChildren(Marker.RESOURCE_TYPE, Marker.class);
    }

    /**
     * How much of this content's history one person has seen.
     *
     * @param userId the viewer's canonical user id, as the repository spells it — not the spelling they typed at login,
     *            since those differ and two spellings of one person would be two markers
     * @return their marker, or {@code null} if they have never looked
     */
    @Nullable
    public Marker getMarker(@NotNull final String userId)
    {
        return this.getChild(userId, Marker.RESOURCE_TYPE, Marker.class);
    }
}
