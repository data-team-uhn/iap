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

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseService;

/**
 * Reads a finished parse into the repository.
 *
 * <p>The daemon leaves everything it produced on the shared volume: a Markdown rendition of the document and the
 * PDF it was read out of. This copies what is kept onto the {@code sub:File} node, so the repository, not the
 * volume, is where the parse lives. Clearing the folder afterwards is {@link ParseCompletionHandler}'s, once the
 * repository has committed: until then the volume holds the only copy, and a commit that fails after the delete
 * would lose the parse for good.
 *
 * <p>Only the Markdown and the PDF are kept, and the PDF only when it is not the upload already. Anything else
 * the pipeline made along the way — a {@code .docx} rendered from a {@code .doc}, say — is a step towards those
 * two and is dropped with the rest of the volume.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParseResultIngester.class)
public class ParseResultIngester
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ParseResultIngester.class);

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String NT_FILE = "nt:file";

    private static final String NT_RESOURCE = "nt:resource";

    private static final String JCR_CONTENT = "jcr:content";

    private static final String JCR_DATA = "jcr:data";

    private static final String JCR_MIME_TYPE = "jcr:mimeType";

    private static final String MARKDOWN_MIME_TYPE = "text/markdown";

    private static final String PDF_MIME_TYPE = "application/pdf";

    /** The child node the Markdown is kept under, whatever the parse called the file. */
    private static final String MARKDOWN_CHILD = "markdownFile";

    /** The child node the PDF is kept under, when it is not the upload itself. */
    private static final String PDF_CHILD = "pdfFile";

    /** The child node holding the upload as it was received. */
    private static final String UPLOADED_FILE_CHILD = "uploadedFile";

    /**
     * Who owns the shared volume, and so who says what is on it and who deletes from it.
     *
     * <p>The paths read and removed here are the ones the daemon named, and only the service that staged them
     * knows which root they have to be under. Asking it is what keeps this module from having to know where the
     * volume is mounted, and keeps reading and deleting to the same answer about how far the daemon is
     * trusted.</p>
     */
    @Reference
    private ParseService parseService;

    /**
     * Copy everything a finished parse produced onto the file node.
     *
     * @param file the {@code sub:File} node the parse was for
     * @param markdown the Markdown rendition of the whole document
     * @param pdf the PDF the Markdown was read out of, or {@code null} when the parse produced none; ignored
     *            when the upload is itself a PDF, which is then the only copy kept
     * @param tokens how large the daemon measured the document to be, or {@code null} when it did not say
     * @throws IOException if what the parse produced cannot be read
     * @throws PersistenceException if the repository refuses what was read
     */
    public void ingest(final Resource file, final Path markdown, final Path pdf, final Long tokens)
        throws IOException, PersistenceException
    {
        final ResourceResolver resolver = file.getResourceResolver();
        if (markdown != null && !this.parseService.isStagedPath(markdown.toString())) {
            // The daemon named a file outside the volume it shares with us. Whatever is there, it is not what
            // this parse produced, and reading it would be opening a file of somebody else's choosing.
            LOGGER.warn("The parse for {} named {}, which is not on the shared volume", file.getPath(), markdown);
            recordLostParse(file, markdown);
            return;
        }
        if (markdown == null || !Files.isRegularFile(markdown)) {
            // The daemon said it finished and named this file, and it is not there. Recorded as completed it
            // would hand the extraction an empty document, and the submitter would be told their document
            // could not be identified rather than that the parse was lost.
            recordLostParse(file, markdown);
            return;
        }
        final long startedAt = System.nanoTime();
        final Map<String, Object> properties = new HashMap<>();
        properties.put(ParsePropertyNames.PARSE_STATUS, ParsePropertyNames.STATUS_COMPLETED);
        if (tokens != null) {
            properties.put(ParsePropertyNames.TOKENS, tokens);
        }

        applyProperties(file, properties);
        final long markdownBytes = storeFile(resolver, file, MARKDOWN_CHILD, MARKDOWN_MIME_TYPE, markdown);
        final long pdfBytes = storePdf(resolver, file, pdf);

        LOGGER.info("Parse read in: file={} markdownBytes={} pdfBytes={} tokens={} ms={}", file.getPath(),
            markdownBytes, pdfBytes, tokens, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /** Say on the file that the parse named a Markdown the volume does not have. */
    private static void recordLostParse(final Resource file, final Path markdown) throws PersistenceException
    {
        applyProperties(file, Map.of(
            ParsePropertyNames.PARSE_STATUS, ParsePropertyNames.STATUS_FAILED,
            ParsePropertyNames.PARSE_ERROR, markdown == null
                ? "The parse finished without naming the text it produced"
                : "The parse named " + markdown + ", which is not on the shared volume"));
    }

    /**
     * Take the staging folder off the volume.
     *
     * <p>The folder goes, not the outputs one by one, and it is the documents module that removes it: it staged
     * the file and it is the only side that knows which root a path has to be under before anything is deleted
     * recursively under it. Nothing left there is worth keeping - what was worth reading is in the repository by
     * now, and the copy of the upload is one the repository already holds.
     *
     * @param staged a path inside the folder, as the file recorded it; nothing happens when there is none
     */
    public void discardStaging(final Path staged)
    {
        this.parseService.discardStaging(staged == null ? null : staged.toString());
    }

    /**
     * Keep one of the parse outputs beside the upload it was made from, under a fixed child name rather than
     * the name it had as a file: what matters later is which output it is, not what the upload was called.
     */
    private long storeFile(final ResourceResolver resolver, final Resource file, final String child,
        final String mimeType, final Path output) throws IOException, PersistenceException
    {
        if (output == null || !Files.isRegularFile(output)) {
            return 0;
        }
        // A re-parse of the same file writes over what the last one left, rather than failing on a child that
        // is already there: the newer rendition is the one that matches the newer parse.
        final Resource existing = file.getChild(child);
        if (existing != null) {
            resolver.delete(existing);
        }
        // Streamed, not read into a byte array first. A 50 MB upload can render to a PDF of the same order, and
        // holding two copies of it on the heap to hand one to the repository is two copies too many.
        try (InputStream content = Files.newInputStream(output)) {
            createFile(resolver, file, child, mimeType, content);
        }
        return Files.size(output);
    }

    /**
     * Keep the PDF the Markdown was read out of, unless the upload is that PDF already. A PDF upload is staged
     * for the parse under the name the pipeline wants, so what comes back is the upload itself: storing it again
     * would put two copies of the same bytes on the node. {@code File.getFilePdf} knows to fall back to the
     * upload, so callers never have to.
     */
    private long storePdf(final ResourceResolver resolver, final Resource file, final Path pdf)
        throws IOException, PersistenceException
    {
        if (pdf != null && !this.parseService.isStagedPath(pdf.toString())) {
            // Same rule as the Markdown: a rendition named off the volume is not one this parse wrote
            LOGGER.warn("The parse for {} named a PDF at {}, which is not on the shared volume", file.getPath(),
                pdf);
            return 0;
        }
        if (isUploadAPdf(file)) {
            LOGGER.debug("The upload is the PDF; not keeping a second copy of it");
            return 0;
        }
        return storeFile(resolver, file, PDF_CHILD, PDF_MIME_TYPE, pdf);
    }

    private static boolean isUploadAPdf(final Resource file)
    {
        final Resource content = file.getChild(UPLOADED_FILE_CHILD + "/" + JCR_CONTENT);
        return content != null
            && PDF_MIME_TYPE.equals(content.getValueMap().get(JCR_MIME_TYPE, String.class));
    }

    private static void applyProperties(final Resource file, final Map<String, Object> properties)
        throws PersistenceException
    {
        final ModifiableValueMap target = file.adaptTo(ModifiableValueMap.class);
        if (target == null) {
            throw new PersistenceException("Not allowed to record the parse on " + file.getPath());
        }
        target.putAll(properties);
    }

    private static void createFile(final ResourceResolver resolver, final Resource parent, final String name,
        final String mimeType, final InputStream content) throws PersistenceException
    {
        final Resource file = resolver.create(parent, name, Map.of(PRIMARY_TYPE, NT_FILE));
        resolver.create(file, JCR_CONTENT, Map.of(
            PRIMARY_TYPE, NT_RESOURCE,
            JCR_MIME_TYPE, mimeType,
            JCR_DATA, content));
    }
}
