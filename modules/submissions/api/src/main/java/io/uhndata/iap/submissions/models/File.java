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
 * derived from it — the Markdown and the PDF.
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

    /** The name of the child node holding the Markdown the parse produced. */
    private static final String MARKDOWN_CHILD = "markdownFile";

    /**
     * The name of the child node holding the PDF LibreOffice rendered from an office document. Absent when the
     * upload was already a PDF: that one is the PDF, and a second copy of it is dead weight.
     */
    private static final String PDF_CHILD = "pdfFile";

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
     * The PDF the parse read, wherever it ended up: the upload itself when a PDF was uploaded, otherwise the one
     * LibreOffice rendered from an office document. Kept for showing the document back to a reviewer at the page
     * a quote came from.
     *
     * <p>A PDF upload is stored once, as the upload. Callers ask for the PDF and get it either way.
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
     * Whether the upload arrived as a PDF, which is what decides where the PDF of this document lives.
     *
     * @return {@code true} if the upload is a PDF
     */
    private boolean isUploadAPdf()
    {
        final Resource content = this.resource.getChild(UPLOADED_FILE_CHILD + "/" + JCR_CONTENT);
        return content != null
            && PDF_MIME_TYPE.equals(content.getValueMap().get(JCR_MIME_TYPE, String.class));
    }

    /**
     * Every rendition the parsing pipeline produced, named or not: the Markdown, the PDF, and anything else made
     * along the way. The upload itself is not one of them.
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

}
