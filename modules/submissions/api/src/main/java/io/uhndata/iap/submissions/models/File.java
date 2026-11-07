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
import io.uhndata.iap.utils.Referenceable;

/**
 * A Sling Model wrapping a {@code sub:File} node: a single uploaded file, plus everything the parsing pipeline
 * derived from it: the Markdown and the PDF.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = File.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class File extends EntityPart implements Referenceable
{
    /** The {@code sling:resourceType} of a {@code sub:File} node. */
    public static final String RESOURCE_TYPE = "sub/File";

    /** The name of the child node holding the upload as it was received. */
    public static final String UPLOADED_FILE_CHILD = "uploadedFile";

    /** The name of the child node holding the Markdown the parse produced, whatever name the parse gave it. */
    public static final String MARKDOWN_CHILD = "markdownFile";

    /**
     * The name of the child node holding the PDF LibreOffice made from an office document. Absent when the
     * upload is already a PDF, since one copy is enough.
     */
    public static final String PDF_CHILD = "pdfFile";

    private static final String FILE_RESOURCE_TYPE = "nt:file";

    private static final String JCR_CONTENT = "jcr:content";

    private static final String JCR_MIME_TYPE = "jcr:mimeType";

    private static final String PDF_MIME_TYPE = "application/pdf";

    @ValueMapValue
    private String parseStatus;

    @ValueMapValue
    private String parseError;

    @ValueMapValue
    private Long tokens;

    /**
     * The document revision this file belongs to.
     *
     * @return the revision, or {@code null} when this file does not sit under one
     */
    @Nullable
    public DocumentVersion getVersion()
    {
        return this.getParent(DocumentVersion.RESOURCE_TYPE, DocumentVersion.class);
    }

    /**
     * Where the parse got to: queued, completed or failed. Only the parse job record uses "active". Kept here as
     * well as on the job, so that a file that failed parsing does not look like one that was never parsed.
     *
     * @return one of {@code queued}, {@code active}, {@code completed} and {@code failed}; {@code null} if none is
     *         recorded
     */
    @Nullable
    public String getParseStatus()
    {
        return this.parseStatus;
    }

    /**
     * Why parsing failed.
     *
     * @return the error message; {@code null} if none is recorded
     */
    @Nullable
    public String getParseError()
    {
        return this.parseError;
    }

    /**
     * The token count of the Markdown rendition, estimated as its length in characters divided by 4.
     *
     * @return the token count; {@code null} if none is recorded
     */
    @Nullable
    public Long getTokens()
    {
        return this.tokens;
    }

    /**
     * The upload, exactly as it was received.
     *
     * @return the {@code nt:file} resource; {@code null} if the upload is not stored yet
     */
    @Nullable
    public Resource getUploadedFile()
    {
        return this.resource.getChild(UPLOADED_FILE_CHILD);
    }

    /**
     * The Markdown the parse produced, which is what the extraction reads.
     *
     * @return the Markdown file, or {@code null} if the file has not been parsed
     */
    @Nullable
    public Resource getFileMarkdown()
    {
        return this.resource.getChild(MARKDOWN_CHILD);
    }

    /**
     * Returns the PDF of this document: the upload when it is a PDF, otherwise the PDF LibreOffice made from the
     * office file. It is kept so a reviewer can see the document at the page a quote came from.
     *
     * <p>A PDF upload is stored only once, as the upload, so callers get the PDF from here either way.
     *
     * @return the PDF file, or {@code null} if there is no PDF of this document
     */
    @Nullable
    public Resource getFilePdf()
    {
        final Resource rendered = this.resource.getChild(PDF_CHILD);
        if (rendered != null) {
            return rendered;
        }
        return isUploadAPdf() ? getUploadedFile() : null;
    }

    /**
     * Whether the upload is a PDF, by its MIME type. This decides where the PDF of this document is stored.
     *
     * @return {@code true} if the upload is a PDF
     */
    public boolean isUploadAPdf()
    {
        final Resource content = this.resource.getChild(UPLOADED_FILE_CHILD + "/" + JCR_CONTENT);
        return content != null
            && PDF_MIME_TYPE.equals(content.getValueMap().get(JCR_MIME_TYPE, String.class));
    }

    /**
     * Every rendition stored on this file: the Markdown, the PDF, and any other file child. The node type allows
     * other renditions, but the pipeline stores none today. The upload itself is not a rendition.
     *
     * @return the {@code nt:file} resources; empty if nothing has been rendered yet
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

    @Override
    @NotNull
    public String getIdentifier()
    {
        return this.identifier;
    }
}
