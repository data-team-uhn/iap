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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.submissions.models.Chunk;
import io.uhndata.iap.submissions.models.Chunks;

/**
 * Reads a finished parse into the repository and then removes what it read.
 *
 * <p>The daemon reports nothing back but the fact that it finished: everything it produced is left on the shared
 * volume, as a Markdown rendition of the document, the PDF it was read out of, and a {@code Chunks} directory
 * holding an outline, a catalog and one file per chunk. This copies all of it onto the {@code sub:File} node — so
 * the repository, not the volume, is where the parse lives — and deletes the files, which keeps the volume from
 * accumulating one tree per parse and means no later parse can meet an older one's leftovers.
 *
 * <p>Only the Markdown and the PDF are kept, and the PDF only when it is not the upload already. Anything else
 * the pipeline made along the way — a {@code .docx} rendered from a {@code .doc}, say — is a step towards those
 * two and is dropped with the rest of the volume.
 *
 * <p>Every parse passes through here, which makes this the one place to decide whether the gate can attempt a
 * document at all - see {@link #ingest}. The gate reads that decision; it does not repeat the check.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParseResultIngester.class)
public class ParseResultIngester
{
    /** The name of the outline file the chunker always writes. */
    static final String OUTLINE_NAME = "outline.json";

    /** The name of the catalog file, written only for a document that was chunked. */
    static final String CATALOG_NAME = "catalog.json";

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseResultIngester.class);

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String UNSTRUCTURED = "nt:unstructured";

    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

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

    private static final String CONTENT_CHILD = "content";

    private static final String MARKDOWN_SUFFIX = ".md";

    @Reference
    private LLMConfigurationService configurationService;

    /**
     * Copy everything a finished parse produced onto the file node, then delete it from the volume.
     *
     * <p>A document left unchunked for a reason other than {@link ParsePropertyNames#REASON_BELOW_MIN_STRUCTURE_TOKENS}
     * that still does not fit {@code wholeDocumentTokenLimit} - the same threshold that decides whether a
     * document is small enough to chunk at all - is marked {@link ParsePropertyNames#UNCHUNKED_OVER_LIMIT} and
     * logged as an error instead: there is nothing safe to process it with, so none of the Markdown, the PDF
     * or a chunk tree is worth keeping for it either.
     *
     * @param file the {@code sub:File} node the parse was for
     * @param markdown the Markdown rendition of the whole document
     * @param pdf the PDF the Markdown was read out of, or {@code null} when the parse produced none; ignored
     *            when the upload is itself a PDF, which is then the only copy kept
     * @param chunks the directory holding the outline, the catalog and the chunk files, or {@code null} when the
     *            daemon produced none
     * @throws IOException if what the parse produced cannot be read
     * @throws PersistenceException if the repository refuses what was read
     */
    public void ingest(final Resource file, final Path markdown, final Path pdf, final Path chunks)
        throws IOException, PersistenceException
    {
        final ResourceResolver resolver = file.getResourceResolver();
        final Map<String, Object> properties = new HashMap<>();
        properties.put(ParsePropertyNames.PARSE_STATUS, ParsePropertyNames.STATUS_COMPLETED);
        properties.putAll(readOutline(chunks));

        final boolean isUnchunkedOverLimit = isUnchunkedOverLimit(properties);
        if (isUnchunkedOverLimit) {
            properties.put(ParsePropertyNames.UNCHUNKED_OVER_LIMIT, Boolean.TRUE);
            final Object reason = properties.get(ParsePropertyNames.UNCHUNKED_REASON);
            final Object tokens = properties.get(ParsePropertyNames.TOKENS);
            LOGGER.error("{} was left unchunked for a reason other than its size ({}), and is {} tokens, past "
                + "the whole-document limit for the active model; the gate cannot process it, whether or not "
                + "it carries bookmarks", file.getPath(), reason, tokens);
        }

        applyProperties(file, properties);
        if (!isUnchunkedOverLimit) {
            storeFile(resolver, file, MARKDOWN_CHILD, MARKDOWN_MIME_TYPE, markdown);
            storePdf(resolver, file, pdf);
            storeChunks(resolver, file, chunks);
        }

        discard(markdown);
        discard(pdf);
        discard(chunks);
    }

    /**
     * Read the properties of outline.json : TOKENS, CHUNKED, UNCHUNKED_REASON, BOOKMARKS and returns the map.
     * If file could not be opened, or the JSON could not be read, return a map with CHUNKED set to false.
     *
     * @param chunks the directory the outline lives in, or {@code null}
     * @return the properties to set on the file node
     */
    private Map<String, Object> readOutline(final Path chunks) throws IOException
    {
        final Map<String, Object> properties = new HashMap<>();
        final Path outline = chunks == null ? null : chunks.resolve(OUTLINE_NAME);
        if (outline == null || !Files.isRegularFile(outline)) {
            properties.put(ParsePropertyNames.CHUNKED, Boolean.FALSE);
            return properties;
        }
        final JsonObject read = readJsonObject(outline);
        if (read.containsKey(ParsePropertyNames.TOKENS)) {
            properties.put(ParsePropertyNames.TOKENS, (long) read.getInt(ParsePropertyNames.TOKENS, 0));
        }
        properties.put(ParsePropertyNames.CHUNKED, read.getBoolean(ParsePropertyNames.CHUNKED, false));
        final String unchunkedReason = read.getString(ParsePropertyNames.UNCHUNKED_REASON, null);
        if (unchunkedReason != null) {
            properties.put(ParsePropertyNames.UNCHUNKED_REASON, unchunkedReason);
        }
        final String[] bookmarks = strings(read.getJsonArray(ParsePropertyNames.BOOKMARKS));
        if (bookmarks.length > 0) {
            properties.put(ParsePropertyNames.BOOKMARKS, bookmarks);
        }
        return properties;
    }

    /**
     * A document left unchunked for a reason other than {@link ParsePropertyNames#REASON_BELOW_MIN_STRUCTURE_TOKENS}
     * says nothing about its size on that basis alone; if it also does not fit
     * {@code wholeDocumentTokenLimit} - the same threshold that decides whether a document is small enough to
     * chunk at all - there is nothing safe to process it with.
     *
     * @param properties what {@link #readOutline} read out of the outline
     * @return {@code true} if this document cannot be processed
     */
    private boolean isUnchunkedOverLimit(final Map<String, Object> properties)
    {
        if (!Boolean.FALSE.equals(properties.get(ParsePropertyNames.CHUNKED))) {
            return false;
        }
        if (ParsePropertyNames.REASON_BELOW_MIN_STRUCTURE_TOKENS.equals(
            properties.get(ParsePropertyNames.UNCHUNKED_REASON))) {
            return false;
        }
        final Object tokens = properties.get(ParsePropertyNames.TOKENS);
        if (!(tokens instanceof Long)) {
            return false;
        }
        return (Long) tokens > resolveDocumentLimitTokens();
    }

    /**
     * The same size threshold the chunker itself uses to decide whether a document is small enough to leave
     * whole - {@code wholeDocumentTokenLimit} - resolved tolerantly: a document is only ever kept from the
     * gate over this, never let through because it could not be checked, so a settings problem here is
     * answered with the documented default rather than thrown.
     *
     * @return the whole-document token limit for the active model, or the default if it could not be resolved
     */
    private long resolveDocumentLimitTokens()
    {
        if (this.configurationService == null) {
            return LLMSettings.DEFAULT_WHOLE_DOCUMENT_TOKEN_LIMIT;
        }
        try {
            return this.configurationService.getActiveSettings().getWholeDocumentTokenLimit();
        } catch (IOException e) {
            LOGGER.warn("Could not resolve the active model's whole-document token limit; falling back to the "
                + "default: {}", e.getMessage());
            return LLMSettings.DEFAULT_WHOLE_DOCUMENT_TOKEN_LIMIT;
        }
    }

    /**
     * Keep one of the parse outputs beside the upload it was made from, under a fixed child name rather than
     * the name it had as a file: what matters later is which output it is, not what the upload was called.
     */
    private void storeFile(final ResourceResolver resolver, final Resource file, final String child,
        final String mimeType, final Path output) throws IOException, PersistenceException
    {
        if (output == null || !Files.isRegularFile(output)) {
            return;
        }
        createFile(resolver, file, child, mimeType, Files.readAllBytes(output));
    }

    /**
     * Keep the PDF the Markdown was read out of, unless the upload is that PDF already. A PDF upload is staged
     * for the parse under the name the pipeline wants, so what comes back is the upload itself: storing it again
     * would put two copies of the same bytes on the node. {@code File.getFilePdf} knows to fall back to the
     * upload, so callers never have to.
     */
    private void storePdf(final ResourceResolver resolver, final Resource file, final Path pdf)
        throws IOException, PersistenceException
    {
        if (isUploadAPdf(file)) {
            LOGGER.debug("The upload is the PDF; not keeping a second copy of it");
            return;
        }
        storeFile(resolver, file, PDF_CHILD, PDF_MIME_TYPE, pdf);
    }

    private static boolean isUploadAPdf(final Resource file)
    {
        final Resource content = file.getChild(UPLOADED_FILE_CHILD + "/" + JCR_CONTENT);
        return content != null
            && PDF_MIME_TYPE.equals(content.getValueMap().get(JCR_MIME_TYPE, String.class));
    }

    /**
     * Copy the chunk tree in, in the order the catalog lists it, which is the order a reader would meet it.
     */
    private void storeChunks(final ResourceResolver resolver, final Resource file, final Path chunks)
        throws IOException, PersistenceException
    {
        final Path catalog = chunks == null ? null : chunks.resolve(CATALOG_NAME);
        if (catalog == null || !Files.isRegularFile(catalog)) {
            return;
        }
        final Resource holder = resolver.create(file, ParsePropertyNames.CHUNKS_CHILD,
            Map.of(PRIMARY_TYPE, UNSTRUCTURED, SLING_RESOURCE_TYPE, Chunks.RESOURCE_TYPE));
        for (final JsonValue entry : readJsonArray(catalog)) {
            storeChunk(resolver, holder, chunks, entry.asJsonObject());
        }
    }

    private void storeChunk(final ResourceResolver resolver, final Resource holder, final Path chunks,
        final JsonObject entry) throws IOException, PersistenceException
    {
        final String chunkId = entry.getString(ParsePropertyNames.JSON_CHUNK_ID, null);
        if (chunkId == null || chunkId.isBlank()) {
            LOGGER.warn("Skipping a catalog entry that names no chunk");
            return;
        }
        final Map<String, Object> properties = new HashMap<>();
        properties.put(PRIMARY_TYPE, UNSTRUCTURED);
        properties.put(SLING_RESOURCE_TYPE, Chunk.RESOURCE_TYPE);
        putIfPresent(properties, entry, ParsePropertyNames.PAGE_START);
        putIfPresent(properties, entry, ParsePropertyNames.PAGE_END);

        final Resource chunk = resolver.create(holder, nodeName(chunkId), properties);
        final Path text = chunks.resolve(chunkId);
        if (Files.isRegularFile(text)) {
            createFile(resolver, chunk, CONTENT_CHILD, MARKDOWN_MIME_TYPE, Files.readAllBytes(text));
        } else {
            LOGGER.warn("The catalog names a chunk with no file beside it");
        }
    }

    /**
     * The node name for a chunk: the chunk's file name, without the extension.
     */
    private static String nodeName(final String chunkId)
    {
        return chunkId.endsWith(MARKDOWN_SUFFIX)
            ? chunkId.substring(0, chunkId.length() - MARKDOWN_SUFFIX.length())
            : chunkId;
    }

    private static void putIfPresent(final Map<String, Object> properties, final JsonObject entry,
        final String name)
    {
        if (entry.containsKey(name) && !entry.isNull(name)) {
            properties.put(name, (long) entry.getInt(name));
        }
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
        final String mimeType, final byte[] content) throws PersistenceException
    {
        final Resource file = resolver.create(parent, name, Map.of(PRIMARY_TYPE, NT_FILE));
        resolver.create(file, JCR_CONTENT, Map.of(
            PRIMARY_TYPE, NT_RESOURCE,
            JCR_MIME_TYPE, mimeType,
            JCR_DATA, new ByteArrayInputStream(content)));
    }

    private static JsonObject readJsonObject(final Path path) throws IOException
    {
        try (JsonReader reader = Json.createReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            return reader.readObject();
        } catch (RuntimeException e) {
            throw new IOException("Could not read " + path.getFileName(), e);
        }
    }

    private static JsonArray readJsonArray(final Path path) throws IOException
    {
        try (JsonReader reader = Json.createReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            return reader.readArray();
        } catch (RuntimeException e) {
            throw new IOException("Could not read " + path.getFileName(), e);
        }
    }

    private static String[] strings(final JsonArray array)
    {
        if (array == null) {
            return new String[0];
        }
        final List<String> values = new ArrayList<>(array.size());
        for (final JsonValue value : array) {
            if (value instanceof JsonString) {
                values.add(((JsonString) value).getString());
            }
        }
        return values.toArray(new String[0]);
    }

    /**
     * Remove what was just read. A file that cannot be deleted is logged rather than thrown: the parse is
     * already in the repository, and failing the ingest over a leftover would lose that.
     */
    private void discard(final Path path)
    {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            deleteRecursively(path);
        } catch (IOException e) {
            LOGGER.warn("Could not remove a parse output after reading it in: {}", e.getMessage());
        }
    }

    /**
     * Delete a file, or a directory and everything under it. Overridden in tests, which cannot readily make the
     * filesystem refuse a deletion.
     *
     * @param path what to delete
     * @throws IOException if it cannot be deleted
     */
    protected void deleteRecursively(final Path path) throws IOException
    {
        try (Stream<Path> walk = Files.walk(path)) {
            final List<Path> deepestFirst = walk.sorted(Comparator.reverseOrder()).toList();
            for (final Path each : deepestFirst) {
                Files.deleteIfExists(each);
            }
        }
    }
}
