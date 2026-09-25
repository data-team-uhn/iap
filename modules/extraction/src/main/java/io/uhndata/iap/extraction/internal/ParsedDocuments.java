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
import java.util.Calendar;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.submissions.models.File;

/**
 * Reads a parsed document, once per reading rather than once per step.
 *
 * <p>Several steps of a reading want the same Markdown: one intake per requirement the reading asks about. Each
 * used to read it out of the repository itself - a whole binary property read and decoded
 * from UTF-8, then normalized again for every quote checked against it. For a 200,000-token protocol that is
 * about 800 kB, several times over, in a walk that reads one document.</p>
 *
 * <p>So the most recent one is kept. One entry, not a cache with a policy: the steps of a reading run one after
 * another on the same document, which is exactly what one slot serves, and a second submission being read at the
 * same time simply replaces it and reads its own. The entry is keyed by the stored file and when it was last
 * written, so a re-parse is never served the document it replaced.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParsedDocuments.class)
public class ParsedDocuments
{
    /** When the stored Markdown was last written, which is what makes a kept copy stale. */
    private static final String JCR_LAST_MODIFIED = "jcr:lastModified";

    private static final String JCR_CONTENT = "jcr:content";

    /** The document read most recently, or {@code null} when nothing has been read yet. */
    private volatile Entry kept;

    /**
     * The parsed text of a file, prepared for the quotes that will be checked against it.
     *
     * @param file the parsed file
     * @return the scan, {@link DocumentScan#EMPTY} when the file holds no parsed text
     * @throws IOException if the stored text cannot be read
     */
    public DocumentScan scan(final File file) throws IOException
    {
        final Resource markdown = file.getFileMarkdown();
        if (markdown == null) {
            return DocumentScan.EMPTY;
        }
        final String key = markdown.getPath();
        final Calendar writtenAt = lastModified(markdown);
        final Entry current = this.kept;
        if (current != null && current.matches(key, writtenAt)) {
            return current.scan;
        }
        final DocumentScan scan = DocumentScan.of(DocumentText.readText(markdown));
        this.kept = new Entry(key, writtenAt, scan);
        return scan;
    }

    /**
     * Drop the text kept from the last reading. A stop deletes that Markdown, and the next reading must not be
     * served a document the submitter just threw out.
     */
    public void forget()
    {
        this.kept = null;
    }

    /**
     * When the stored Markdown was last written.
     *
     * @param markdown the {@code nt:file} holding it
     * @return the moment, or {@code null} when the repository did not record one
     */
    private static Calendar lastModified(final Resource markdown)
    {
        final Resource content = markdown.getChild(JCR_CONTENT);
        return content == null ? null : content.getValueMap().get(JCR_LAST_MODIFIED, Calendar.class);
    }

    /**
     * One document, and what makes it the same document next time.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class Entry
    {
        private final String path;

        private final Calendar writtenAt;

        private final DocumentScan scan;

        Entry(final String path, final Calendar writtenAt, final DocumentScan scan)
        {
            this.path = path;
            this.writtenAt = writtenAt;
            this.scan = scan;
        }

        /**
         * Whether this entry is still the document being asked for.
         *
         * <p>A file the repository records no write time for is never reused: there would be nothing to tell a
         * re-parse from the parse it replaced, and serving the old text would read answers out of a document
         * the submitter has already taken down.</p>
         */
        boolean matches(final String otherPath, final Calendar otherWrittenAt)
        {
            return this.writtenAt != null && otherWrittenAt != null && this.path.equals(otherPath)
                && this.writtenAt.getTimeInMillis() == otherWrittenAt.getTimeInMillis();
        }
    }
}
