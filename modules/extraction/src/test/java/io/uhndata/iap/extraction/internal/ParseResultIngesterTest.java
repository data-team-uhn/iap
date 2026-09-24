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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.sling.api.resource.ModifiableValueMap;
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

import io.uhndata.iap.documents.api.ParseService;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** What the ingester asked to have taken off the volume. */
    private final List<String> discarded = new ArrayList<>();

    @TempDir
    private Path volume;

    private Path markdown;

    private Path pdf;

    @BeforeEach
    void setUp() throws Exception
    {
        this.ingester = ingesterWith(this.discarded::add);
        this.context.create().resource(FILE_PATH, Map.of("sling:resourceType", "sub/File"));
        this.markdown = this.volume.resolve("proposal.md");
        Files.writeString(this.markdown, MARKDOWN, StandardCharsets.UTF_8);
        this.pdf = this.volume.resolve("proposal.pdf");
        Files.writeString(this.pdf, PDF, StandardCharsets.UTF_8);
    }

    /**
     * An ingester whose volume is a list of what it asked to have removed.
     *
     * <p>Removing belongs to the documents module now: it staged the file and it is the only side that knows
     * which root a path has to be under before anything is deleted recursively under it. So this records the
     * request rather than touching a filesystem.</p>
     */
    private static ParseResultIngester ingesterWith(final Consumer<String> onDiscard) throws Exception
    {
        final ParseResultIngester built = new ParseResultIngester();
        final Field field = ParseResultIngester.class.getDeclaredField("parseService");
        field.setAccessible(true);
        field.set(built, new RecordingParseService(onDiscard));
        return built;
    }

    /** A file node that refuses to be written, so the ingest has something real to fail on. */
    private static final class ReadOnlyResource extends ResourceWrapper
    {
        ReadOnlyResource(final Resource wrapped)
        {
            super(wrapped);
        }

        @Override
        public <T> T adaptTo(final Class<T> type)
        {
            if (ModifiableValueMap.class.equals(type)) {
                return null;
            }
            return super.adaptTo(type);
        }
    }

    /** Records what it was asked to take off the volume. */
    private static final class RecordingParseService implements ParseService
    {
        private final Consumer<String> onDiscard;

        RecordingParseService(final Consumer<String> onDiscard)
        {
            this.onDiscard = onDiscard;
        }

        @Override
        public String stage(final String fileName, final InputStream content)
        {
            throw new UnsupportedOperationException("an ingest stages nothing");
        }

        @Override
        public String queue(final String path, final String target)
        {
            throw new UnsupportedOperationException("an ingest queues nothing");
        }

        @Override
        public void discardStaging(final String stagedPath)
        {
            this.onDiscard.accept(stagedPath);
        }

        @Override
        public boolean isStagedPath(final String path)
        {
            // The real service answers from where the volume is mounted; here anything the test wrote is on it
            return path != null && !path.contains("elsewhere");
        }
    }

    private Resource file()
    {
        return this.context.resourceResolver().getResource(FILE_PATH);
    }

    private ValueMap properties()
    {
        return file().getValueMap();
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

    private void ingestADocument() throws IOException
    {
        this.ingester.ingest(file(), this.markdown, this.pdf, 12000L);
    }

    @Test
    void recordsWhatTheParseSaidAboutTheDocument() throws IOException
    {
        ingestADocument();

        final ValueMap properties = properties();
        assertEquals("completed", properties.get("parseStatus", String.class));
        assertEquals(12000L, properties.get("tokens", Long.class));
    }

    @Test
    void keepsTheMarkdownOfTheWholeDocument() throws IOException
    {
        ingestADocument();

        final Resource stored = file().getChild("markdownFile");
        assertNotNull(stored, "the Markdown is kept under a fixed name, not the one the file had");
        assertEquals(MARKDOWN, textOf(stored));
        assertEquals("text/markdown", mimeTypeOf(stored));
    }

    @Test
    void keepsThePdfTheMarkdownWasReadOutOf() throws IOException
    {
        ingestADocument();

        final Resource stored = file().getChild("pdfFile");
        assertNotNull(stored);
        assertEquals(PDF, textOf(stored));
        assertEquals("application/pdf", mimeTypeOf(stored));
    }

    @Test
    void doesNotKeepAPdfTheUploadAlreadyIs() throws IOException
    {
        uploadOfType("application/pdf");

        ingestADocument();

        assertNull(file().getChild("pdfFile"), "the upload is the PDF; one copy is enough");
    }

    @Test
    void keepsARenderedPdfWhenTheUploadIsAnOfficeDocument() throws IOException
    {
        uploadOfType("application/msword");

        ingestADocument();

        assertNotNull(file().getChild("pdfFile"), "nothing else holds the PDF LibreOffice made");
    }

    @Test
    void copesWithNoPdfToKeep() throws IOException
    {
        Files.delete(this.pdf);

        this.ingester.ingest(file(), this.markdown, this.pdf, 12000L);

        assertEquals("completed", properties().get("parseStatus", String.class));
        assertNotNull(file().getChild("markdownFile"));
        assertNull(file().getChild("pdfFile"));
    }

    // Reading in does not clear the volume: what has been read is in an open transaction, and a commit that
    // failed after the delete would leave neither a parse nor the outputs to make one from again
    @Test
    void leavesTheStagingFolderWhereItIsUntilSomebodyHasCommitted() throws IOException
    {
        final Path leftover = this.volume.resolve("proposal.docx");
        Files.writeString(leftover, "the staged upload", StandardCharsets.UTF_8);

        ingestADocument();

        assertEquals(List.of(), this.discarded, "clearing the volume is the committer's, not the ingester's");
        assertTrue(Files.exists(leftover), "the ingester itself deletes nothing");
    }

    @Test
    void copesWithAParseTheDaemonDidNotMeasure() throws IOException
    {
        this.ingester.ingest(file(), this.markdown, this.pdf, null);

        assertEquals("completed", properties().get("parseStatus", String.class));
        assertNull(properties().get("tokens", Long.class), "a daemon that said nothing leaves the size unset");
        assertNotNull(file().getChild("markdownFile"));
    }

    // A parse that says it finished and names text the volume does not have has not finished. Recorded as
    // completed it would send an empty document on to be judged, and the document would take the blame.
    @Test
    void refusesToCallAParseDoneWhenItsTextIsNotThere() throws IOException
    {
        Files.delete(this.markdown);

        this.ingester.ingest(file(), this.markdown, this.pdf, 12000L);

        assertEquals("failed", properties().get("parseStatus", String.class));
        assertTrue(properties().get("parseError", String.class).contains("not on the shared volume"));
        assertNull(file().getChild("markdownFile"));
        assertEquals(List.of(), this.discarded, "and clearing the volume is still the committer's");
    }

    @Test
    void refusesToCallAParseDoneWhenItNamedNoTextAtAll() throws IOException
    {
        this.ingester.ingest(file(), null, this.pdf, 12000L);

        assertEquals("failed", properties().get("parseStatus", String.class));
        assertNull(file().getChild("markdownFile"));
    }

    @Test
    void refusesToRecordAParseOnSomethingItCannotWrite()
    {
        final Resource readOnly = new ReadOnlyResource(file());

        assertThrows(PersistenceException.class,
            () -> this.ingester.ingest(readOnly, this.markdown, this.pdf, 12000L));
    }

    @Test
    void clearsTheStagingFolderForAParseThatFailed()
    {
        this.ingester.discardStaging(this.markdown);
    }

    @Test
    void hasNothingToClearForAFileThatWasNeverStaged()
    {
        this.ingester.discardStaging(null);

        assertTrue(Files.exists(this.volume));
    }

    // The paths come from the daemon, over a channel this side does not control the contents of. Deleting
    // already refuses a path outside the shared root; reading has to refuse the same ones, or the two disagree
    // about how far the daemon is trusted.
    @Test
    void refusesToReadTextFromOutsideTheSharedVolume() throws IOException
    {
        final Path elsewhere = this.markdown.getParent().resolve("elsewhere.md");
        Files.writeString(elsewhere, "# Not ours\n");

        this.ingester.ingest(file(), elsewhere, this.pdf, 12000L);

        assertEquals("failed", properties().get("parseStatus", String.class));
        assertTrue(properties().get("parseError", String.class).contains("not on the shared volume"));
        assertNull(file().getChild("markdownFile"));
    }

    @Test
    void refusesToKeepAPdfFromOutsideTheSharedVolume() throws IOException
    {
        final Path elsewhere = this.pdf.getParent().resolve("elsewhere.pdf");
        Files.write(elsewhere, new byte[] { 1, 2, 3 });
        uploadOfType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        this.ingester.ingest(file(), this.markdown, elsewhere, 12000L);

        assertEquals("completed", properties().get("parseStatus", String.class),
            "the text was ours, so the parse still landed");
        assertNotNull(file().getChild("markdownFile"));
        assertNull(file().getChild("pdfFile"), "but the rendition named off the volume is not kept");
    }
}
