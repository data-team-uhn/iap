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

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sub:File} node: a single uploaded file, plus everything the parsing pipeline
 * derived from it — the renditions, the outline and the chunk tree.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = File.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class File extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:File} node. */
    public static final String RESOURCE_TYPE = "sub/File";

    /** The name of the child node holding the upload as it was received. */
    private static final String UPLOADED_FILE_CHILD = "uploadedFile";

    /** The name of the child node holding the outline. */
    private static final String TOC_CHILD = "toc";

    /** The name of the child node holding the chunk tree. */
    private static final String CHUNKS_CHILD = "chunks";

    private static final String FILE_RESOURCE_TYPE = "nt:file";

    @ValueMapValue
    private String parseStatus;

    @ValueMapValue
    private String parseError;

    @ValueMapValue
    private Long tokens;

    @ValueMapValue
    private Long backmatterLine;

    @ValueMapValue
    private boolean chunked;

    @ValueMapValue
    private String unchunkedReason;

    /**
     * Where the parse got to: queued, active, completed or failed. Kept here as well as on the parse job so that a
     * file that failed parsing does not look like one that was never parsed.
     *
     * @return a status, or {@code null} if parsing has not been requested
     */
    @Nullable
    public String getParseStatus()
    {
        return this.parseStatus;
    }

    /**
     * Why the parse failed.
     *
     * @return an error message, or {@code null} if parsing did not fail
     */
    @Nullable
    public String getParseError()
    {
        return this.parseError;
    }

    /**
     * The token count of the Markdown rendition. A cheap character heuristic rather than an ML tokenizer's count.
     *
     * @return a token count, or {@code null} if the file has not been parsed
     */
    @Nullable
    public Long getTokens()
    {
        return this.tokens;
    }

    /**
     * The line of the first Reference or Appendix record that resolved to a body line. Everything from there to
     * the end of the document becomes one standalone backmatter chunk.
     *
     * @return a line number, or {@code null} if the document has no backmatter
     */
    @Nullable
    public Long getBackmatterLine()
    {
        return this.backmatterLine;
    }

    /**
     * Whether the document was split into chunks. False means it was small enough to work on whole, so there is no
     * chunk tree.
     *
     * @return {@code true} if there is a chunk tree
     */
    public boolean isChunked()
    {
        return this.chunked;
    }

    /**
     * Why no chunks were produced, set only when the document was not chunked, so that a missing chunk tree always
     * says which it was: a deliberate skip, or a failure.
     *
     * @return a reason, or {@code null} if the document was chunked
     */
    @Nullable
    public String getUnchunkedReason()
    {
        return this.unchunkedReason;
    }

    /**
     * The upload, exactly as it was received.
     *
     * @return the uploaded file, or {@code null} if the upload has not landed yet
     */
    @Nullable
    public Resource getUploadedFile()
    {
        return this.resource.getChild(UPLOADED_FILE_CHILD);
    }

    /**
     * The renditions the parsing pipeline produced: the Markdown the extraction reads, and the intermediate
     * formats made along the way. The upload itself is not one of them.
     *
     * @return a list of file resources, empty if nothing has been rendered yet
     */
    @NotNull
    public List<Resource> getRenditions()
    {
        final List<Resource> result = new ArrayList<>();
        for (final Resource child : this.resource.getChildren()) {
            if (child.isResourceType(FILE_RESOURCE_TYPE) && !UPLOADED_FILE_CHILD.equals(child.getName())) {
                result.add(child);
            }
        }
        return result;
    }

    /**
     * The document's outline.
     *
     * @return an outline, or {@code null} if none was found
     */
    @Nullable
    public ToC getToc()
    {
        return this.getChild(TOC_CHILD, ToC.RESOURCE_TYPE, ToC.class);
    }

    /**
     * The chunk tree.
     *
     * @return the chunks, or {@code null} if the document was not chunked
     */
    @Nullable
    public Chunks getChunks()
    {
        return this.getChild(CHUNKS_CHILD, Chunks.RESOURCE_TYPE, Chunks.class);
    }
}
