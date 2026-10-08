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

import org.apache.sling.api.resource.Resource;

/**
 * The parsed document's text: reading it, listing its headings, and placing a quote in it.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DocumentText
{
    /**
     * An ATX heading: up to six hashes, a space, then the title, which is the one group. The hashes are not
     * captured because nothing reads the level, and the title is not length-capped because a capped one simply
     * stopped counting as a heading - so a long one titled nothing and placed no quote under itself.
     */
    private static final Pattern ATX_HEADING = Pattern.compile("^#{1,6}\\s+(\\S.*)$");

    private static final String JCR_CONTENT = "jcr:content";

    private static final String JCR_DATA = "jcr:data";

    private DocumentText()
    {
        // Utility
    }

    /**
     * Read the text of a stored file.
     *
     * @param file the {@code nt:file} holding it, or {@code null}
     * @return the text, or an empty string when there is no file to read
     * @throws IOException if the stored text cannot be read
     */
    static String readText(final Resource file) throws IOException
    {
        if (file == null) {
            return "";
        }
        final Resource content = file.getChild(JCR_CONTENT);
        if (content == null) {
            return "";
        }
        final InputStream stored = content.getValueMap().get(JCR_DATA, InputStream.class);
        if (stored == null) {
            return "";
        }
        try (InputStream data = stored) {
            return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(data.readAllBytes())).toString();
        }
    }

    /**
     * Whether one line of the document is an ATX heading, and what it says.
     *
     * @param line one line of the document
     * @return the matcher, already applied
     */
    static Matcher matchHeading(final String line)
    {
        return ATX_HEADING.matcher(line.strip());
    }
}
