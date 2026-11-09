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
 * Reads a parsed document once per reading instead of once per step.
 *
 * <p>The steps of a reading run one after another on the same document, so only the last document read is kept.
 * It is keyed by the stored file and the time it was last written, so a new parse never gets the old text.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParsedDocuments.class)
public class ParsedDocuments
{
    /** When the stored Markdown was last written; a change means the kept copy is stale. */
    private static final String JCR_LAST_MODIFIED = "jcr:lastModified";

    private static final String JCR_CONTENT = "jcr:content";

    /** The document read last, or {@code null} when nothing has been read yet. */
    private volatile Entry kept;

    /**
     * Returns the parsed text of a file, ready for checking quotes against it.
     *
     * @param file the parsed file
     * @return the scan, or {@link DocumentScan#EMPTY_SCAN} when the file holds no parsed text
     * @throws IOException if the stored text cannot be read
     */
    public DocumentScan scan(final File file) throws IOException
    {
        final Resource markdown = file.getMarkdownFile();
        if (markdown == null) {
            return DocumentScan.EMPTY_SCAN;
        }
        final String key = markdown.getPath();
        final Calendar writtenAt = getLastModified(markdown);
        final Entry current = this.kept;
        if (current != null && current.isSameVersion(key, writtenAt)) {
            return current.scan;
        }
        final DocumentScan scan = DocumentScan.of(DocumentText.readText(markdown));
        this.kept = new Entry(key, writtenAt, scan);
        return scan;
    }

    /**
     * Returns when the stored Markdown was last written.
     *
     * @param markdown the {@code nt:file} holding it
     * @return the time, or {@code null} when the repository has none
     */
    private static Calendar getLastModified(final Resource markdown)
    {
        final Resource content = markdown.getChild(JCR_CONTENT);
        return content == null ? null : content.getValueMap().get(JCR_LAST_MODIFIED, Calendar.class);
    }

    /**
     * One kept document, with what tells whether it is still the same document.
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
         * Checks whether this entry is still the document asked for.
         *
         * <p>A file with no recorded write time is never reused: nothing would tell a new parse from the one it
         * replaced, and the old text could give answers from a document the submitter already removed.</p>
         */
        boolean isSameVersion(final String otherPath, final Calendar otherWrittenAt)
        {
            return this.writtenAt != null && otherWrittenAt != null && this.path.equals(otherPath)
                && this.writtenAt.getTimeInMillis() == otherWrittenAt.getTimeInMillis();
        }
    }
}
