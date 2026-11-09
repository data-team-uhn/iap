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
package io.uhndata.iap.extraction.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseService;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.submissions.models.File;

/**
 * Reads a finished parse into the repository by copying its outputs onto the {@code sub:File} node.
 *
 * <p>The daemon leaves its outputs on the shared volume: the Markdown of the document and, for an office
 * document, the PDF LibreOffice made of it. {@link ParseCompletionHandler} deletes the folder later, only after
 * the repository has committed, since until then the volume holds the only copy.</p>
 *
 * <p>Only the Markdown and the PDF are kept, and the PDF only when the upload is not itself a PDF. Anything else
 * the pipeline made on the way, such as a {@code .docx} made from a {@code .doc}, is dropped with the folder.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParseResultIngester.class)
public class ParseResultIngester
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ParseResultIngester.class);

    private static final String MARKDOWN_MIME_TYPE = "text/markdown";

    private static final String PDF_MIME_TYPE = "application/pdf";

    /**
     * The owner of the shared volume, which says whether a path is on it and deletes from it.
     *
     * <p>The paths read and deleted here come from the daemon, and only the service that staged the files knows
     * which root they must be under. Asking it means this module does not need to know where the volume is
     * mounted, and reading and deleting trust the daemon's paths in the same way.</p>
     */
    @Reference
    private ParseService parseService;

    /**
     * Copies what a finished parse produced onto the file node. When the Markdown is missing or not on the shared
     * volume, it marks the parse failed instead.
     *
     * @param file the {@code sub:File} node the parse was for
     * @param markdown the path of the Markdown of the whole document
     * @param pdf the path of the PDF of the document, or {@code null} when the parse made none; ignored
     *            when the upload is itself a PDF, which is then the only copy kept
     * @param tokens the size of the document in tokens, or {@code null} when the daemon did not say
     * @throws IOException if what the parse produced cannot be read
     * @throws PersistenceException if the repository refuses what was read
     */
    public void ingest(final Resource file, final Path markdown, final Path pdf, final Long tokens)
        throws IOException, PersistenceException
    {
        final ResourceResolver resolver = file.getResourceResolver();
        if (markdown != null && !this.parseService.isStagedPath(markdown.toString())) {
            // A file outside the shared volume is not this parse's output, and reading it would open a file
            // someone else chose
            LOGGER.warn("The parse for {} named {}, which is not on the shared volume", file.getPath(), markdown);
            // A daemon that names such a file is misconfigured, or is not our daemon
            ErrorLogger.logProblem("The document daemon named a file outside the shared volume",
                ErrorContext.of(ParseResultIngester.class, "ingest").about(file).with("named", markdown));
            recordLostParse(file, markdown, "which is not on the shared volume");
            return;
        }
        if (markdown == null || !Files.isRegularFile(markdown)) {
            // The daemon said it finished, but the file is not there. Marked completed, the reading would get an
            // empty document and tell the submitter it could not be identified, instead of that the parse was lost.
            LOGGER.warn("The parse for {} named {}, which is not there", file.getPath(), markdown);
            ErrorLogger.logProblem("The document daemon named a result that is not there",
                ErrorContext.of(ParseResultIngester.class, "ingest").about(file).with("named", markdown));
            recordLostParse(file, markdown, "which is not there");
            return;
        }
        final long startedAt = System.nanoTime();
        final Map<String, Object> properties = new HashMap<>();
        properties.put(ParsePropertyNames.PARSE_STATUS_PROPERTY, ParsePropertyNames.STATUS_COMPLETED);
        if (tokens != null) {
            properties.put(ParsePropertyNames.TOKENS_PROPERTY, tokens);
        }

        writeParseProperties(file, properties);
        final long markdownBytes = storeFile(resolver, file, File.MARKDOWN_CHILD, MARKDOWN_MIME_TYPE, markdown);
        final long pdfBytes = storePdf(resolver, file, pdf);

        LOGGER.info("Parse read in: file={} markdownBytes={} pdfBytes={} tokens={} ms={}", file.getPath(),
            markdownBytes, pdfBytes, tokens, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /** Marks the parse failed on the file, saying which Markdown could not be read and why. */
    private static void recordLostParse(final Resource file, final Path markdown, final String why)
        throws PersistenceException
    {
        writeParseProperties(file, Map.of(
            ParsePropertyNames.PARSE_STATUS_PROPERTY, ParsePropertyNames.STATUS_FAILED,
            ParsePropertyNames.PARSE_ERROR_PROPERTY, markdown == null
                ? "The parse finished without naming the text it produced"
                : "The parse named " + markdown + ", " + why));
    }

    /**
     * Deletes the staging folder from the shared volume.
     *
     * <p>The whole folder goes, not each output. The documents module deletes it, since it staged the file and
     * only it knows which root a path must be under before a recursive delete. Nothing in the folder is needed any
     * more: the outputs are in the repository by now, and so is the upload.</p>
     *
     * @param staged a path inside the staging folder, from the parse outcome or the file's {@code sharedPath};
     *        nothing happens when it is {@code null}
     * @return {@code false} when the folder is still there
     */
    public boolean discardStaging(final Path staged)
    {
        return this.parseService.discardStaging(staged == null ? null : staged.toString());
    }

    /**
     * Stores one parse output under the file node, with a fixed child name instead of its file name: what matters
     * later is which output it is, not what the upload was called. Returns the size in bytes, or 0 when the
     * output is missing.
     */
    private long storeFile(final ResourceResolver resolver, final Resource file, final String child,
        final String mimeType, final Path output) throws IOException, PersistenceException
    {
        if (output == null || !Files.isRegularFile(output)) {
            return 0;
        }
        // A new parse of the same file replaces the old output instead of failing on a child that already exists
        final Resource existing = file.getChild(child);
        if (existing != null) {
            resolver.delete(existing);
        }
        // Streamed, not read into memory first: a 50 MB upload can give a PDF of about the same size
        try (InputStream content = Files.newInputStream(output)) {
            createFile(resolver, file, child, mimeType, content);
        }
        return Files.size(output);
    }

    /**
     * Stores the PDF of the document, unless the upload is already a PDF: then the PDF next to the Markdown is
     * the upload, and one copy is enough. {@code File.getPdfFile} falls back to the upload, so callers do not have
     * to. Returns the size in bytes, or 0 when nothing was stored.
     */
    private long storePdf(final ResourceResolver resolver, final Resource file, final Path pdf)
        throws IOException, PersistenceException
    {
        if (pdf != null && !this.parseService.isStagedPath(pdf.toString())) {
            // Same rule as the Markdown: a PDF off the shared volume is not one this parse wrote
            LOGGER.warn("The parse for {} named a PDF at {}, which is not on the shared volume", file.getPath(),
                pdf);
            ErrorLogger.logProblem("The document daemon named a file outside the shared volume",
                ErrorContext.of(ParseResultIngester.class, "ingest").about(file).with("named", pdf));
            return 0;
        }
        final File model = file.adaptTo(File.class);
        if (model != null && model.isUploadAPdf()) {
            LOGGER.debug("The upload is the PDF; not keeping a second copy of it");
            return 0;
        }
        return storeFile(resolver, file, File.PDF_CHILD, PDF_MIME_TYPE, pdf);
    }

    /**
     * Writes what a parse came to on its file, checking the submission out first: a checked-in submission is read
     * only, with everything under it.
     *
     * @param file the file the parse was for
     * @param properties the properties to write
     * @throws PersistenceException if the submission cannot be checked out or the file cannot be written
     */
    static void writeParseProperties(final Resource file, final Map<String, Object> properties)
        throws PersistenceException
    {
        // Always the first write of an ingest, so checking the submission out here covers every write after it
        final Resource submission = SubmissionFiles.findSubmission(file);
        if (submission != null) {
            ExtractionStatus.checkOut(submission);
        }
        final ModifiableValueMap target = file.adaptTo(ModifiableValueMap.class);
        if (target == null) {
            throw new PersistenceException("Not allowed to record the parse on " + file.getPath());
        }
        target.putAll(properties);
    }

    private static void createFile(final ResourceResolver resolver, final Resource parent, final String name,
        final String mimeType, final InputStream content) throws PersistenceException
    {
        final Resource file = resolver.create(parent, name, Map.of(JcrConstants.JCR_PRIMARYTYPE, JcrConstants.NT_FILE));
        resolver.create(file, JcrConstants.JCR_CONTENT, Map.of(
            JcrConstants.JCR_PRIMARYTYPE, JcrConstants.NT_RESOURCE,
            JcrConstants.JCR_MIMETYPE, mimeType,
            JcrConstants.JCR_DATA, content));
    }
}
