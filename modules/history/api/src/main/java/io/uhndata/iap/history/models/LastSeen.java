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
 * The container of one piece of content's {@link Marker read markers}, one per person who has looked at it.
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
     * The markers of everybody who has looked at this content.
     *
     * @return one marker per person, in no particular order; empty if nobody has looked
     */
    @NotNull
    public List<Marker> getMarkers()
    {
        return this.getChildren(Marker.RESOURCE_TYPE, Marker.class);
    }

    /**
     * One person's marker on this content.
     *
     * @param userId the person's canonical username: the repository's spelling of their account name, which can differ
     *            from what they typed at login
     * @return their marker, or {@code null} if they have never looked at this content
     */
    @Nullable
    public Marker getMarker(@NotNull final String userId)
    {
        return this.getChild(userId, Marker.RESOURCE_TYPE, Marker.class);
    }
}
