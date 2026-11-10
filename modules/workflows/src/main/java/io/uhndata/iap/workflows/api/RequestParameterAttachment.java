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
package io.uhndata.iap.workflows.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.apache.sling.api.request.RequestParameter;
import org.jetbrains.annotations.NotNull;

/**
 * A multipart file part, offered to a handler as an attachment: how a servlet that turns a request into an event
 * passes a file along.
 *
 * <p>
 * Holds the part rather than its bytes: Sling has already buffered it wherever it saw fit, and copying it into the
 * heap to hand it over would double that for no reason — a handler writes it straight into the repository.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class RequestParameterAttachment implements EventAttachment
{
    private final RequestParameter part;

    /**
     * An attachment reading a file part.
     *
     * @param part a part that is a file rather than a form field
     */
    public RequestParameterAttachment(@NotNull final RequestParameter part)
    {
        this.part = part;
    }

    @Override
    public String getFileName()
    {
        return this.part.getFileName();
    }

    @Override
    public String getMimeType()
    {
        return this.part.getContentType();
    }

    @Override
    public long getSize()
    {
        return this.part.getSize();
    }

    @Override
    public InputStream openStream() throws IOException
    {
        // Sling declares this nullable, and the annotation is load-bearing rather than defensive: a part with
        // nothing to read is a broken request rather than an empty file, since a zero-byte upload still has a
        // stream. Asserted rather than branched on, so there is no path a test cannot reach
        return Objects.requireNonNull(this.part.getInputStream(), "A file part always has content to read");
    }
}
