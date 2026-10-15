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
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.models.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParsedDocuments}: the document is read once for a reading, not once per step, and a
 * re-parse is never served the text it replaced.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ParsedDocumentsTest
{
    private static final String FILE_PATH = "/Submissions/aRequest/d1/v1/file";

    private static final String MARKDOWN = "# Protocol\n\nThe aim is to find out.\n";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final ParsedDocuments documents = new ParsedDocuments();

    @BeforeEach
    void setUp()
    {
        this.context.create().resource(FILE_PATH, Map.of("sling:resourceType", File.RESOURCE_TYPE));
    }

    private File getFile()
    {
        final File model = this.context.resourceResolver().getResource(FILE_PATH).adaptTo(File.class);
        assertNotNull(model);
        return model;
    }

    private void storeMarkdown(final String text, final Calendar writtenAt)
    {
        final Resource markdown = this.context.create().resource(FILE_PATH + "/markdownFile",
            Map.of("jcr:primaryType", "nt:file"));
        final Map<String, Object> content = new HashMap<>(Map.of(
            "jcr:primaryType", "nt:resource",
            "jcr:data", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
        if (writtenAt != null) {
            content.put("jcr:lastModified", writtenAt);
        }
        this.context.create().resource(markdown.getPath() + "/jcr:content", content);
    }

    private void rewrite(final String text, final Calendar writtenAt) throws Exception
    {
        final Resource content =
            this.context.resourceResolver().getResource(FILE_PATH + "/markdownFile/jcr:content");
        final ModifiableValueMap properties = content.adaptTo(ModifiableValueMap.class);
        assertNotNull(properties);
        properties.put("jcr:data", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        properties.put("jcr:lastModified", writtenAt);
        this.context.resourceResolver().commit();
    }

    private static Calendar createCalendarAt(final int minute)
    {
        final Calendar when = Calendar.getInstance();
        when.set(2026, Calendar.JUNE, 1, 9, minute, 0);
        when.set(Calendar.MILLISECOND, 0);
        return when;
    }

    @Test
    void readsTheDocumentItIsAskedFor() throws IOException
    {
        storeMarkdown(MARKDOWN, createCalendarAt(0));

        assertEquals(MARKDOWN, this.documents.scan(getFile()).getText());
    }

    // The point of the whole class: four steps of one reading ask for the same document
    @Test
    void handsBackTheSameReadingToEveryStepOfOne() throws IOException
    {
        storeMarkdown(MARKDOWN, createCalendarAt(0));

        assertSame(this.documents.scan(getFile()), this.documents.scan(getFile()));
    }

    @Test
    void readsAgainOnceTheDocumentHasBeenRewritten() throws Exception
    {
        storeMarkdown(MARKDOWN, createCalendarAt(0));
        final DocumentScan first = this.documents.scan(getFile());

        rewrite("# Protocol\n\nSomething else entirely.\n", createCalendarAt(5));
        final DocumentScan second = this.documents.scan(getFile());

        assertNotSame(first, second);
        assertTrue(second.getText().contains("Something else entirely"));
    }

    // Nothing to tell a re-parse from the parse it replaced, so the kept copy is never reused
    @Test
    void neverReusesADocumentTheRepositoryRecordsNoWriteTimeFor() throws IOException
    {
        storeMarkdown(MARKDOWN, null);

        assertNotSame(this.documents.scan(getFile()), this.documents.scan(getFile()));
    }

    @Test
    void hasNothingToReadForAFileThatWasNeverParsed() throws IOException
    {
        assertSame(DocumentScan.EMPTY, this.documents.scan(getFile()));
    }

    @Test
    void readsADifferentFileRatherThanAnswerWithTheLastOne() throws IOException
    {
        storeMarkdown(MARKDOWN, createCalendarAt(0));
        final DocumentScan first = this.documents.scan(getFile());

        this.context.create().resource("/Submissions/aRequest/d2/v1/file",
            Map.of("sling:resourceType", File.RESOURCE_TYPE));
        final Resource other = this.context.create().resource("/Submissions/aRequest/d2/v1/file/markdownFile",
            Map.of("jcr:primaryType", "nt:file"));
        this.context.create().resource(other.getPath() + "/jcr:content", Map.of(
            "jcr:primaryType", "nt:resource",
            "jcr:lastModified", createCalendarAt(0),
            "jcr:data", new ByteArrayInputStream("# Another\n".getBytes(StandardCharsets.UTF_8))));

        final File second = this.context.resourceResolver()
            .getResource("/Submissions/aRequest/d2/v1/file").adaptTo(File.class);
        assertNotNull(second);
        assertNotSame(first, this.documents.scan(second));
        assertEquals("# Another\n", this.documents.scan(second).getText());
    }

    @Test
    void readsEmptyTextFromAMarkdownWithNoContent() throws IOException
    {
        final Resource markdown = this.context.create().resource(FILE_PATH + "/markdownFile",
            Map.of("jcr:primaryType", "nt:file"));
        assertNotNull(markdown);

        assertSame(DocumentScan.EMPTY.getText(), this.documents.scan(getFile()).getText());
    }
}
