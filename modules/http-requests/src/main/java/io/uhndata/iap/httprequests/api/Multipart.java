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
package io.uhndata.iap.httprequests.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The parts of a {@code multipart/form-data} body, in the order they are sent.
 *
 * <p>
 * A part's content is only opened while the request is being sent, and read straight onto the connection, so a body
 * can carry files far larger than anything that should be held in memory.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class Multipart
{
    /** Line breaks would end a part's headers early, and let a content type add headers of its own. */
    private static final Pattern CONTROL_CHARACTER_PATTERN = Pattern.compile("\\p{Cntrl}");

    private final List<Part> parts = new ArrayList<>();

    /**
     * Where a part's content comes from.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface Content
    {
        /**
         * Opens the content for reading. The stream is closed once it has been sent.
         *
         * @return the content
         * @throws IOException if the content cannot be read, which fails the request
         */
        @NotNull
        InputStream open() throws IOException;
    }

    /**
     * One part of the body.
     *
     * @param name the name of the form field the part fills
     * @param filename the name of the file the part carries, or {@code null} for a plain field
     * @param contentType the media type of the content
     * @param content the content
     * @version $Id$
     * @since 0.1.0
     */
    public record Part(@NotNull String name, @Nullable String filename, @NotNull String contentType,
        @NotNull Content content)
    {
    }

    /**
     * Adds a plain field, encoded in UTF-8.
     *
     * @param name the name of the field
     * @param value the value of the field
     * @param contentType the media type of the value, e.g. {@code application/json}
     * @return this body, for chaining
     * @throws IllegalArgumentException if the content type contains line breaks or other control characters
     */
    @NotNull
    public Multipart add(@NotNull final String name, @NotNull final String value, @NotNull final String contentType)
    {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return add(name, null, contentType, () -> new ByteArrayInputStream(bytes));
    }

    /**
     * Adds a part read from elsewhere, usually a file.
     *
     * @param name the name of the field
     * @param filename the name of the file, or {@code null} for a plain field
     * @param contentType the media type of the content, e.g. {@code application/pdf}
     * @param content where to read the content from when the request is sent
     * @return this body, for chaining
     * @throws IllegalArgumentException if the content type contains line breaks or other control characters
     */
    @NotNull
    public Multipart add(@NotNull final String name, @Nullable final String filename,
        @NotNull final String contentType, @NotNull final Content content)
    {
        if (CONTROL_CHARACTER_PATTERN.matcher(contentType).find()) {
            throw new IllegalArgumentException("A part's content type cannot contain control characters");
        }
        this.parts.add(new Part(name, filename, contentType, content));
        return this;
    }

    /**
     * The parts added so far.
     *
     * @return the parts, in the order they were added
     */
    @NotNull
    public List<Part> getParts()
    {
        return Collections.unmodifiableList(this.parts);
    }
}
