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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.jetbrains.annotations.NotNull;

/**
 * A filled template, as the editable {@code .docx} and the PDF made from it.
 *
 * <p>The PDF's hash is taken here, from the bytes this platform received, so that whoever stores the PDF can later
 * show that the one sent on is the one the submitter was shown.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class GeneratedDocument
{
    /** The media type of {@link #getDocx()}. */
    public static final String DOCX_MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** The media type of {@link #getPdf()}. */
    public static final String PDF_MIME_TYPE = "application/pdf";

    private final byte[] docx;

    private final byte[] pdf;

    private final String pdfSha256;

    /**
     * Record a generated document.
     *
     * @param docx the filled template
     * @param pdf the PDF converted from it
     */
    public GeneratedDocument(@NotNull final byte[] docx, @NotNull final byte[] pdf)
    {
        this.docx = docx.clone();
        this.pdf = pdf.clone();
        this.pdfSha256 = digest(pdf, "SHA-256");
    }

    /**
     * The filled template.
     *
     * @return the {@code .docx} bytes, a copy
     */
    @NotNull
    public byte[] getDocx()
    {
        return this.docx.clone();
    }

    /**
     * The PDF converted from the filled template.
     *
     * @return the PDF bytes, a copy
     */
    @NotNull
    public byte[] getPdf()
    {
        return this.pdf.clone();
    }

    /**
     * The SHA-256 of the PDF.
     *
     * @return 64 lowercase hexadecimal characters
     */
    @NotNull
    public String getPdfSha256()
    {
        return this.pdfSha256;
    }

    /**
     * The hex digest of some content. The algorithm is a parameter so a test can name one that does not exist.
     *
     * @param content what to hash
     * @param algorithm the digest algorithm
     * @return lowercase hexadecimal
     * @throws IllegalStateException when the platform lacks the algorithm, which no Java platform does for SHA-256
     */
    static String digest(final byte[] content, final String algorithm)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
