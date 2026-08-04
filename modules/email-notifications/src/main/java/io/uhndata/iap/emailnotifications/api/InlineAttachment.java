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
package io.uhndata.iap.emailnotifications.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A file sent along with an email and displayed inside it, usually an image referenced from the HTML body as
 * {@code src="cid:<name>"}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class InlineAttachment
{
    private final String name;

    private final String mimeType;

    private final byte[] content;

    /**
     * Basic constructor.
     *
     * @param name the name of the attachment, which is how the body references it
     * @param mimeType the media type of the content
     * @param content the content itself, {@code null} for an empty attachment
     */
    public InlineAttachment(@NotNull final String name, @NotNull final String mimeType,
        @Nullable final byte[] content)
    {
        this.name = name;
        this.mimeType = mimeType;
        this.content = content == null ? new byte[0] : content.clone();
    }

    /**
     * The name of the attachment, and the content ID the body references it by.
     *
     * @return a file name, e.g. {@code logo.png}
     */
    @NotNull
    public String getName()
    {
        return this.name;
    }

    /**
     * The media type of the content.
     *
     * @return a MIME type, e.g. {@code image/png}
     */
    @NotNull
    public String getMimeType()
    {
        return this.mimeType;
    }

    /**
     * The content of the attachment.
     *
     * @return the bytes to send, a copy that callers may modify freely
     */
    @NotNull
    public byte[] getContent()
    {
        return this.content.clone();
    }

    @Override
    @NotNull
    public String toString()
    {
        return this.name + " (" + this.mimeType + ", " + this.content.length + " bytes)";
    }
}
