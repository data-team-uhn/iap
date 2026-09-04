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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A file arriving with an event, for a handler that has somewhere to put it.
 *
 * <p><strong>Why this exists rather than a byte array or a request parameter.</strong> Everything else in an event's
 * payload is a string, because everything else a caller can say fits in one. A file does not: it has a name and a
 * type of its own, and reading it as text corrupts it — the same mistake as decoding a JCR binary through a reader,
 * which turns a byte order mark into a character and a PDF into nonsense.</p>
 *
 * <p>An interface rather than a value object, because the engine's door is not only HTTP. A servlet builds one of
 * these from a multipart part; an inbound mail channel would build one from a MIME part; neither should have to
 * become the other's shape first. The engine itself never looks inside — it carries this to the handler named by
 * the activity, exactly as it carries a string.</p>
 *
 * <p>Nothing here is buffered by contract. {@link #openStream()} is the only way to the content, so an
 * implementation is free to stream from wherever it already holds it, and a handler should write it straight into
 * the repository rather than reading it into memory to look at it first.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface EventAttachment
{
    /**
     * The name the file arrived under, for storing it as something a person recognises.
     *
     * @return the file name, or {@code null} if the caller sent none
     */
    @Nullable
    String getFileName();

    /**
     * What the caller says the file is.
     *
     * <p>Said by the caller, so it is a claim rather than a fact: a handler that cares whether a document really is
     * what it says it is has to look at the content. It is recorded because the repository needs a mime type to
     * serve the file back with, and because refusing an unacceptable type is cheaper before reading anything.</p>
     *
     * @return the declared media type, or {@code null} if the caller declared none
     */
    @Nullable
    String getMimeType();

    /**
     * Opens the file's content.
     *
     * @return a stream over the bytes, which the caller closes
     * @throws IOException if the content cannot be read
     */
    @NotNull
    InputStream openStream() throws IOException;
}
