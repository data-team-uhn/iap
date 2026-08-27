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
package io.uhndata.iap.submissions.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sub:Chunk} node: one chunk of a document, as the chunking pipeline wrote it. The
 * catalog's {@code chunk_id} is the node's own name, available through {@link #getName()}, and the chunk's length
 * is the length of its content file.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Chunk.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Chunk extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Chunk} node. */
    public static final String RESOURCE_TYPE = "sub/Chunk";

    /** The name of the child node holding this chunk's Markdown. */
    private static final String CONTENT_CHILD = "content";

    @ValueMapValue
    private String summary;

    @ValueMapValue
    private String[] rubricTags;

    @ValueMapValue
    private String tagBasis;

    @ValueMapValue
    private boolean uncertain;

    @ValueMapValue
    private Long pageStart;

    @ValueMapValue
    private Long pageEnd;

    /**
     * A short summary of the chunk, written by the summarizer in a background pass.
     *
     * @return a summary, or {@code null} if the chunk has not been summarized yet
     */
    @Nullable
    public String getSummary()
    {
        return this.summary;
    }

    /**
     * What this chunk is about, tagged from the same vocabulary the questions are tagged with. Chunk selection for
     * extraction is tag-driven: a chunk is read when its tags intersect those of the questions being extracted.
     *
     * @return a list of tags, empty if the chunk has not been tagged yet
     */
    @NotNull
    public List<String> getRubricTags()
    {
        return this.rubricTags == null ? List.of() : List.of(this.rubricTags);
    }

    /**
     * How the tags were arrived at: from the list of headings alone, from a model reading the whole chunk, or from
     * a later and more thorough pass. Only the last two count as content-based.
     *
     * @return a basis, or {@code null} if the chunk has not been tagged yet
     */
    @Nullable
    public String getTagBasis()
    {
        return this.tagBasis;
    }

    /**
     * Whether the tags are a weak guess, or were filled in after a model reply came back truncated.
     *
     * @return {@code true} if the tags are not to be trusted
     */
    public boolean isUncertain()
    {
        return this.uncertain;
    }

    /**
     * The first page marker this chunk covers, so that an extracted answer can be cited back to a page of the
     * source. Absent for documents that came in as DOCX, which carry no page markers.
     *
     * @return a page number, or {@code null} if the source has no page markers
     */
    @Nullable
    public Long getPageStart()
    {
        return this.pageStart;
    }

    /**
     * The last page marker this chunk covers.
     *
     * @return a page number, or {@code null} if the source has no page markers
     */
    @Nullable
    public Long getPageEnd()
    {
        return this.pageEnd;
    }

    /**
     * This chunk's Markdown.
     *
     * @return the content file, or {@code null} if it has not been written yet
     */
    @Nullable
    public Resource getContent()
    {
        return this.resource.getChild(CONTENT_CHILD);
    }
}
