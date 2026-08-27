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
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.submissions.models.Chunk;
import io.uhndata.iap.submissions.models.Chunks;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParseResultIngester}: what a finished parse becomes in the repository, and that it
 * stops taking up room on the volume once it is there.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ParseResultIngesterTest
{
    private static final String FILE_PATH = "/Submissions/s1/proposal/v1/file";

    private static final String MARKDOWN = "# Proposal\n\nThe whole document.\n";

    /** Stands in for the PDF: the ingester copies bytes and never looks at them. */
    private static final String PDF = "%PDF-1.7 the whole document";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private ParseResultIngester ingester;

    @TempDir
    private Path volume;

    private Path markdown;

    private Path pdf;

    private Path chunks;

    @BeforeEach
    void setUp() throws IOException
    {
        this.ingester = new ParseResultIngester();
        this.context.create().resource(FILE_PATH, Map.of("sling:resourceType", "sub/File"));
        this.markdown = this.volume.resolve("proposal.md");
        Files.writeString(this.markdown, MARKDOWN, StandardCharsets.UTF_8);
        this.pdf = this.volume.resolve("proposal.pdf");
        Files.writeString(this.pdf, PDF, StandardCharsets.UTF_8);
        this.chunks = Files.createDirectories(this.volume.resolve("Chunks"));
    }

    private Resource file()
    {
        return this.context.resourceResolver().getResource(FILE_PATH);
    }

    private ValueMap properties()
    {
        return file().getValueMap();
    }

    private void writeOutline(final String json) throws IOException
    {
        Files.writeString(this.chunks.resolve(ParseResultIngester.OUTLINE_NAME), json, StandardCharsets.UTF_8);
    }

    private void writeCatalog(final String json) throws IOException
    {
        Files.writeString(this.chunks.resolve(ParseResultIngester.CATALOG_NAME), json, StandardCharsets.UTF_8);
    }

    private void writeChunk(final String name, final String text) throws IOException
    {
        Files.writeString(this.chunks.resolve(name), text, StandardCharsets.UTF_8);
    }

    /**
     * Wires an active model with the given whole-document token limit. Left unset,
     * {@code configurationService} stays {@code null} and the ingester falls back to
     * {@link LLMSettings#DEFAULT_WHOLE_DOCUMENT_TOKEN_LIMIT}, same as it does when resolving fails.
     */
    private void setWholeDocumentTokenLimit(final long limit) throws Exception
    {
        setConfigurationService(() -> new LLMSettings("test",
            new LLMSettings.ProviderSettings(null, null, 0, null), "test",
            new LLMSettings.ModelSettings(0, 0, 0, 0, limit, null, null)));
    }

    private void setConfigurationServiceFailure(final IOException failure) throws Exception
    {
        setConfigurationService(() -> {
            throw failure;
        });
    }

    private void setConfigurationService(final LLMConfigurationService service) throws Exception
    {
        final Field field = ParseResultIngester.class.getDeclaredField("configurationService");
        field.setAccessible(true);
        field.set(this.ingester, service);
    }

    /** Stands the upload up on the node, as intake will, so the ingester can see what was uploaded. */
    private void uploadOfType(final String mimeType) throws PersistenceException
    {
        final Resource upload = this.context.resourceResolver()
            .create(file(), "uploadedFile", Map.of("jcr:primaryType", "nt:file"));
        this.context.resourceResolver().create(upload, "jcr:content",
            Map.of("jcr:primaryType", "nt:resource", "jcr:mimeType", mimeType));
        this.context.resourceResolver().commit();
    }

    private static String textOf(final Resource ntFile) throws IOException
    {
        final Resource content = ntFile.getChild("jcr:content");
        assertNotNull(content, "the file should carry its content");
        try (InputStream data = content.getValueMap().get("jcr:data", InputStream.class)) {
            return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(data.readAllBytes())).toString();
        }
    }

    private static String mimeTypeOf(final Resource ntFile)
    {
        final Resource content = ntFile.getChild("jcr:content");
        assertNotNull(content, "the file should carry its content");
        return content.getValueMap().get("jcr:mimeType", String.class);
    }

    private void ingestAChunkedDocument() throws IOException
    {
        writeOutline("{\"tokens\": 12000, \"bookmarks\": [\"Background\", \"Methods\"], \"chunked\": true}");
        writeCatalog("[{\"chunk_id\": \"Chunk-1.md\","
            + " \"pageStart\": 1, \"pageEnd\": 4},"
            + " {\"chunk_id\": \"Chunk-2.md\","
            + " \"pageStart\": 5, \"pageEnd\": 9}]");
        writeChunk("Chunk-1.md", "# Background\n\nWhy.\n");
        writeChunk("Chunk-2.md", "# Methods\n\nHow.\n");
        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);
    }

    @Test
    void recordsWhatTheParseSaidAboutTheDocument() throws IOException
    {
        ingestAChunkedDocument();

        final ValueMap properties = properties();
        assertEquals("completed", properties.get("parseStatus", String.class));
        assertEquals(12000L, properties.get("tokens", Long.class));
        assertTrue(properties.get("chunked", Boolean.class));
        assertArrayEquals(new String[]{ "Background", "Methods" }, properties.get("bookmarks", String[].class));
        assertNull(properties.get("unchunkedReason", String.class));
    }

    @Test
    void keepsTheMarkdownOfTheWholeDocument() throws IOException
    {
        ingestAChunkedDocument();

        final Resource stored = file().getChild("markdownFile");
        assertNotNull(stored, "the Markdown is kept under a fixed name, not the one the file had");
        assertEquals(MARKDOWN, textOf(stored));
        assertEquals("text/markdown", mimeTypeOf(stored));
    }

    @Test
    void keepsThePdfTheMarkdownWasReadOutOf() throws IOException
    {
        ingestAChunkedDocument();

        final Resource stored = file().getChild("pdfFile");
        assertNotNull(stored);
        assertEquals(PDF, textOf(stored));
        assertEquals("application/pdf", mimeTypeOf(stored));
    }

    @Test
    void doesNotKeepAPdfTheUploadAlreadyIs() throws IOException
    {
        uploadOfType("application/pdf");

        ingestAChunkedDocument();

        assertNull(file().getChild("pdfFile"), "the upload is the PDF; one copy is enough");
        assertFalse(Files.exists(this.pdf), "it still comes off the volume");
    }

    @Test
    void keepsARenderedPdfWhenTheUploadIsAnOfficeDocument() throws IOException
    {
        uploadOfType("application/msword");

        ingestAChunkedDocument();

        assertNotNull(file().getChild("pdfFile"), "nothing else holds the PDF LibreOffice made");
    }

    @Test
    void copesWithNoPdfToKeep() throws IOException
    {
        Files.delete(this.pdf);
        writeOutline("{\"chunked\": false}");

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertEquals("completed", properties().get("parseStatus", String.class));
        assertNotNull(file().getChild("markdownFile"));
        assertNull(file().getChild("pdfFile"));
    }

    @Test
    void buildsTheChunkTreeInDocumentOrder() throws IOException
    {
        ingestAChunkedDocument();

        final Chunks holder = file().getChild("chunks").adaptTo(Chunks.class);
        assertNotNull(holder);
        final List<Chunk> stored = holder.getChunks();
        assertEquals(2, stored.size());
        assertEquals(1L, stored.get(0).getPageStart());
        assertEquals(4L, stored.get(0).getPageEnd());
        assertEquals(5L, stored.get(1).getPageStart());
        assertEquals("# Background\n\nWhy.\n", textOf(stored.get(0).getContent()));
        assertEquals("# Methods\n\nHow.\n", textOf(stored.get(1).getContent()));
    }

    @Test
    void namesAChunkNodeWithoutTheExtensionItHadAsAFile() throws IOException
    {
        ingestAChunkedDocument();

        assertNotNull(file().getChild("chunks/Chunk-1"));
        assertNull(file().getChild("chunks/Chunk-1.md"));
    }

    @Test
    void takesAChunkIdThatIsNotAFileNameAsItStands() throws IOException
    {
        writeOutline("{\"chunked\": true}");
        writeCatalog("[{\"chunk_id\": \"Chunk-1\"}]");
        writeChunk("Chunk-1", "text");

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertNotNull(file().getChild("chunks/Chunk-1"));
    }

    @Test
    void takesTheParseOffTheVolumeOnceItIsInTheRepository() throws IOException
    {
        ingestAChunkedDocument();

        assertFalse(Files.exists(this.markdown), "the markdown should be gone");
        assertFalse(Files.exists(this.pdf), "the pdf should be gone");
        assertFalse(Files.exists(this.chunks), "the chunk tree should be gone");
    }

    @Test
    void carriesOverWhatTheTaggerWillLaterOverwrite() throws IOException
    {
        writeOutline("{\"chunked\": true}");
        writeCatalog("[{\"chunk_id\": \"Chunk-1.md\"}]");
        writeChunk("Chunk-1.md", "text");

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        final Chunk stored = file().getChild("chunks/Chunk-1").adaptTo(Chunk.class);
        assertNotNull(stored);
        assertNull(stored.getPageStart(), "a document with no page markers has no page bounds");
    }

    @Test
    void recordsADocumentThatWasLeftWhole() throws IOException
    {
        writeOutline("{\"tokens\": 400, \"bookmarks\": [], \"chunked\": false,"
            + " \"unchunkedReason\": \"below the size gate\"}");

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        final ValueMap properties = properties();
        assertFalse(properties.get("chunked", Boolean.class));
        assertEquals("below the size gate", properties.get("unchunkedReason", String.class));
        assertNull(properties.get("bookmarks", String[].class), "an empty bookmark list is left unset");
        assertNull(file().getChild("chunks"), "there is no chunk tree to build");
    }

    @Test
    void marksAndRecordsADocumentThatIsUnchunkedAndOverLimit() throws Exception
    {
        // Unchunked for a reason that says nothing about size, and past the active model's whole-document
        // token limit - the "should not happen" case. The status and the flag are still recorded; none of
        // the Markdown, the PDF or a chunk tree is worth keeping for a document nothing can read.
        writeOutline("{\"tokens\": 50000, \"chunked\": false,"
            + " \"unchunkedReason\": \"splitter_returned_no_parts\"}");
        setWholeDocumentTokenLimit(20_000L);

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertEquals("completed", properties().get("parseStatus", String.class));
        assertEquals(50000L, properties().get("tokens", Long.class));
        assertTrue(properties().get("unchunkedOverLimit", Boolean.class));
    }

    @Test
    void storesNoFilesForADocumentThatIsUnchunkedAndOverLimit() throws Exception
    {
        writeOutline("{\"tokens\": 50000, \"chunked\": false,"
            + " \"unchunkedReason\": \"splitter_returned_no_parts\"}");
        setWholeDocumentTokenLimit(20_000L);

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertNull(file().getChild("markdownFile"), "nothing can read it, so it is not worth keeping");
        assertNull(file().getChild("pdfFile"));
        assertNull(file().getChild("chunks"));
        assertFalse(Files.exists(this.markdown), "the volume is still cleared, same as any other parse");
        assertFalse(Files.exists(this.pdf));
        assertFalse(Files.exists(this.chunks));
    }

    @Test
    void marksItEvenWhenTheDocumentHasItsOwnBookmarks() throws Exception
    {
        // Bookmarks make no difference: a document this large that chunking failed on outright is not
        // something a bare table of contents can be trusted to stand in for.
        writeOutline("{\"tokens\": 50000, \"bookmarks\": [\"Background\"], \"chunked\": false,"
            + " \"unchunkedReason\": \"splitter_returned_no_parts\"}");
        setWholeDocumentTokenLimit(20_000L);

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertTrue(properties().get("unchunkedOverLimit", Boolean.class), "bookmarks do not excuse it");
    }

    @Test
    void doesNotMarkADocumentBelowTheSizeThreshold() throws Exception
    {
        writeOutline("{\"tokens\": 50000, \"chunked\": false,"
            + " \"unchunkedReason\": \"below_min_structure_tokens\"}");
        setWholeDocumentTokenLimit(20_000L);

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertNull(properties().get("unchunkedOverLimit", Boolean.class), "small by the chunker's own count");
    }

    @Test
    void doesNotMarkADocumentThatStillFitsTheActiveModel() throws Exception
    {
        writeOutline("{\"tokens\": 500, \"chunked\": false,"
            + " \"unchunkedReason\": \"chunking_not_requested\"}");
        setWholeDocumentTokenLimit(1_000L);

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertNull(properties().get("unchunkedOverLimit", Boolean.class), "small enough for this model");
    }

    @Test
    void usesTheConfiguredLimitRatherThanTheDefault() throws Exception
    {
        // 50000 tokens is past the default 20000-token limit, but this model is configured with more room -
        // proof the resolved setting is actually used, not just the fallback default.
        writeOutline("{\"tokens\": 50000, \"chunked\": false,"
            + " \"unchunkedReason\": \"chunking_not_requested\"}");
        setWholeDocumentTokenLimit(100_000L);

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertNull(properties().get("unchunkedOverLimit", Boolean.class));
    }

    @Test
    void fallsBackToTheDefaultLimitWhenItCannotBeResolved() throws Exception
    {
        // A settings problem is not treated as proof the document fits - it falls back to the same default
        // the chunker itself uses, not to "anything goes."
        writeOutline("{\"tokens\": 25000, \"chunked\": false,"
            + " \"unchunkedReason\": \"chunking_not_requested\"}");
        setConfigurationServiceFailure(new IOException("no active model is configured"));

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertEquals("completed", properties().get("parseStatus", String.class));
        assertTrue(properties().get("unchunkedOverLimit", Boolean.class), "past the default of 20000");
    }

    @Test
    void doesNotMarkASmallDocumentWhenTheLimitCannotBeResolved() throws Exception
    {
        // The fallback is a real default, not a blanket "cannot verify, so block everything."
        writeOutline("{\"tokens\": 500, \"chunked\": false,"
            + " \"unchunkedReason\": \"chunking_not_requested\"}");
        setConfigurationServiceFailure(new IOException("no active model is configured"));

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertNull(properties().get("unchunkedOverLimit", Boolean.class), "well under the default of 20000");
    }

    @Test
    void copesWithAParseThatProducedNoChunkDirectory() throws IOException
    {
        this.ingester.ingest(file(), this.markdown, this.pdf, null);

        assertEquals("completed", properties().get("parseStatus", String.class));
        assertFalse(properties().get("chunked", Boolean.class));
        assertNotNull(file().getChild("markdownFile"));
    }

    @Test
    void copesWithAChunkDirectoryThatCarriesNoOutline() throws IOException
    {
        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertFalse(properties().get("chunked", Boolean.class));
    }

    @Test
    void copesWithNoMarkdownToKeep() throws IOException
    {
        Files.delete(this.markdown);
        writeOutline("{\"chunked\": false}");

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertEquals("completed", properties().get("parseStatus", String.class));
        assertNull(file().getChild("markdownFile"));
    }

    @Test
    void skipsACatalogEntryThatNamesNoChunk() throws IOException
    {
        writeOutline("{\"chunked\": true}");
        writeCatalog("[{\"summary\": \"nameless\"}, {\"chunk_id\": \"  \"},"
            + " {\"chunk_id\": \"Chunk-1.md\"}]");
        writeChunk("Chunk-1.md", "text");

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        final Chunks holder = file().getChild("chunks").adaptTo(Chunks.class);
        assertNotNull(holder);
        assertEquals(1, holder.getChunks().size());
    }

    @Test
    void keepsAChunkTheCatalogNamesButNoFileBacks() throws IOException
    {
        writeOutline("{\"chunked\": true}");
        writeCatalog("[{\"chunk_id\": \"Chunk-1.md\", \"pageStart\": 2}]");

        this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks);

        final Chunk stored = file().getChild("chunks/Chunk-1").adaptTo(Chunk.class);
        assertNotNull(stored, "what the catalog knows is still worth keeping");
        assertEquals(2L, stored.getPageStart());
        assertNull(stored.getContent());
    }

    @Test
    void refusesAnOutlineItCannotRead() throws IOException
    {
        writeOutline("not json at all");

        final IOException failure =
            assertThrows(IOException.class, () -> this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks));
        assertTrue(failure.getMessage().contains(ParseResultIngester.OUTLINE_NAME));
    }

    @Test
    void refusesACatalogItCannotRead() throws IOException
    {
        writeOutline("{\"chunked\": true}");
        writeCatalog("{\"not\": \"an array\"}");

        final IOException failure =
            assertThrows(IOException.class, () -> this.ingester.ingest(file(), this.markdown, this.pdf, this.chunks));
        assertTrue(failure.getMessage().contains(ParseResultIngester.CATALOG_NAME));
    }

    @Test
    void refusesToRecordAParseOnSomethingItCannotWrite() throws IOException
    {
        writeOutline("{\"chunked\": false}");
        final Resource readOnly = new ReadOnlyResource(file());

        assertThrows(PersistenceException.class,
            () -> this.ingester.ingest(readOnly, this.markdown, this.pdf, this.chunks));
    }

    @Test
    void keepsTheParseEvenWhenTheVolumeWillNotLetGoOfTheFiles() throws IOException
    {
        writeOutline("{\"chunked\": false}");
        final ParseResultIngester stubborn = new ParseResultIngester()
        {
            @Override
            protected void deleteRecursively(final Path path) throws IOException
            {
                throw new IOException("the volume is read-only");
            }
        };

        stubborn.ingest(file(), this.markdown, this.pdf, this.chunks);

        assertEquals("completed", properties().get("parseStatus", String.class));
        assertTrue(Files.exists(this.markdown), "the file is still there, and that is not a failure");
    }

    /**
     * A file node that cannot be written to, standing in for one the caller has no write access to.
     */
    private static final class ReadOnlyResource extends ResourceWrapper
    {
        ReadOnlyResource(final Resource wrapped)
        {
            super(wrapped);
        }

        @Override
        public <T> T adaptTo(final Class<T> type)
        {
            if (org.apache.sling.api.resource.ModifiableValueMap.class.equals(type)) {
                return null;
            }
            return super.adaptTo(type);
        }
    }
}
