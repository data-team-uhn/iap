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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;

/**
 * Reads a parsed document's text and tells whether a line is a heading.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DocumentText
{
    /**
     * A Markdown heading: one to six {@code #}, whitespace, then the title in group 1. The title has no length
     * limit, so a long heading is still a heading and quotes below it are still placed under it.
     */
    private static final Pattern ATX_HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+(\\S.*)$");

    private DocumentText()
    {
        // Utility
    }

    /**
     * Reads the text of a stored file as UTF-8.
     *
     * @param file the {@code nt:file} holding the text, or {@code null}
     * @return the text, or an empty string when the file is {@code null} or has no stored data
     * @throws IOException if the stored text cannot be read
     */
    static String readText(final Resource file) throws IOException
    {
        if (file == null) {
            return "";
        }
        final Resource content = file.getChild(JcrConstants.JCR_CONTENT);
        if (content == null) {
            return "";
        }
        final InputStream stored = content.getValueMap().get(JcrConstants.JCR_DATA, InputStream.class);
        if (stored == null) {
            return "";
        }
        try (InputStream data = stored) {
            return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(data.readAllBytes())).toString();
        }
    }

    /**
     * Checks whether one line of the document is a Markdown heading. Spaces around the line are ignored.
     *
     * @param line one line of the document
     * @return the matcher; call {@code matches()} first, then group 1 is the heading's title
     */
    static Matcher matchHeading(final String line)
    {
        return ATX_HEADING_PATTERN.matcher(line.strip());
    }
}
