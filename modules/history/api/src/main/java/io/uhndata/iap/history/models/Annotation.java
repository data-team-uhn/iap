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
 * A note a person added to one {@link Entry} after it was recorded: a correction, a clarification, or a decision
 * about the change. For example, "this is the revision the approval was granted on", or "this figure was wrong, and
 * here is why".
 *
 * <p>
 * A JCR version cannot be annotated. An {@code nt:version} and its {@code jcr:frozenNode} are both entirely
 * protected: no property, no mixin. A note about a past state of the content therefore lives here, as a child of the
 * entry that recorded the change.
 * </p>
 *
 * <p>
 * Notes are only ever added. Saying something else about the same change adds another annotation.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Annotation.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Annotation extends Content
{
    /** The Sling resource type of a note on a recorded change. */
    public static final String RESOURCE_TYPE = "hist/Annotation";

    @ValueMapValue
    private String author;

    @ValueMapValue
    private String note;

    @ValueMapValue
    private String resolution;

    /**
     * Who wrote the note.
     *
     * @return the canonical username of the author; empty only in a malformed record
     */
    @NotNull
    public String getAuthor()
    {
        return this.author == null ? "" : this.author;
    }

    /**
     * The text of the note.
     *
     * @return the text exactly as the author wrote it; empty only in a malformed record
     */
    @NotNull
    public String getNote()
    {
        return this.note == null ? "" : this.note;
    }

    /**
     * A verdict for software to act on, beside the text, for a note that records a decision rather than a remark.
     *
     * @return a short free-form keyword set by the feature that records the decision, or {@code null} if the note is
     *         only a remark
     */
    @Nullable
    public String getResolution()
    {
        return this.resolution;
    }
}
