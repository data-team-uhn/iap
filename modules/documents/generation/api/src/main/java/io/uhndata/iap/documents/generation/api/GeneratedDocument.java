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
package io.uhndata.iap.documents.generation.api;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.jetbrains.annotations.NotNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * A filled template, as the editable {@code .docx} and the PDF made from it.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class GeneratedDocument
{
    /** The media type of {@link #openDocx()}. */
    public static final String DOCX_MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** The media type of {@link #openPdf()}. */
    public static final String PDF_MIME_TYPE = "application/pdf";

    private final byte[] docx;

    private final byte[] pdf;

    /**
     * Record a generated document.
     *
     * @param docx the filled template
     * @param pdf the PDF converted from it
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
        justification = "A PDF can run to megabytes, and the generator hands over arrays it never touches again")
    public GeneratedDocument(@NotNull final byte[] docx, @NotNull final byte[] pdf)
    {
        this.docx = docx;
        this.pdf = pdf;
    }

    /**
     * The filled template.
     *
     * @return a new stream over the {@code .docx} bytes
     */
    @NotNull
    public InputStream openDocx()
    {
        return new ByteArrayInputStream(this.docx);
    }

    /**
     * The PDF converted from the filled template.
     *
     * @return a new stream over the PDF bytes
     */
    @NotNull
    public InputStream openPdf()
    {
        return new ByteArrayInputStream(this.pdf);
    }
}
