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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;

/**
 * Something somebody said about a past change, after it happened.
 *
 * <p>
 * This exists because a version cannot be annotated. An {@code nt:version} and its {@code jcr:frozenNode} are both
 * entirely protected — no property, no mixin, ever — so a later thought about an earlier state has nowhere to live in
 * version storage. "This is the revision the approval was granted on", "this figure was wrong, and here is why": those
 * live here.
 * </p>
 *
 * <p>
 * Appended, never replaced. Saying something else about the same change adds another one of these, which is what makes
 * the record worth keeping.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Annotation.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Annotation extends Content
{
    /** The Sling resource type of a remark on a past change. */
    public static final String RESOURCE_TYPE = "hist/Annotation";

    /** Who said it. */
    @ValueMapValue
    private String author;

    /** What they said. */
    @ValueMapValue
    private String note;

    /** What it amounts to. */
    @ValueMapValue
    private String resolution;

    /**
     * Who said it, as a canonical user id.
     *
     * @return a user id, empty only in a malformed record
     */
    @NotNull
    public String getAuthor()
    {
        return this.author == null ? "" : this.author;
    }

    /**
     * What they said, in their own words.
     *
     * @return the note, empty only in a malformed record
     */
    @NotNull
    public String getNote()
    {
        return this.note == null ? "" : this.note;
    }

    /**
     * A short machine-readable verdict beside the words, for annotations that are decisions rather than remarks.
     *
     * @return a verdict, or {@code null} when the annotation is only a remark
     */
    @Nullable
    public String getResolution()
    {
        return this.resolution;
    }
}
