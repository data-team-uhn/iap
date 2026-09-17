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
package io.uhndata.iap.documents.api;

import java.io.IOException;
import java.io.InputStream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Parsing a document through the Docling daemon, from Java. The daemon reads files off the volume it shares with
 * the platform and works asynchronously, so a parse is two steps: put the file where the daemon can see it, then
 * queue the job. The outcome arrives later, through the daemon's callback; whoever asked for the parse learns of
 * it through a {@link io.uhndata.iap.documents.spi.ParseOutcomeHandler}, keyed by the {@code target} they named.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ParseService
{
    /**
     * Copy a file onto the shared volume, in a folder of its own, so that the daemon can read it.
     *
     * @param fileName the name the file is known by; kept for its extension, which is what tells the daemon what
     *            kind of document it is, and made safe for a file system
     * @param content the bytes
     * @return the staged file's path, as the daemon sees it
     * @throws IOException if the file cannot be written
     */
    @NotNull
    String stage(@Nullable String fileName, @NotNull InputStream content) throws IOException;

    /**
     * Queue a parse of a file on the shared volume.
     *
     * @param path the file's path on the shared volume, as the daemon sees it
     * @param chunk whether to also split the Markdown into a chunk tree
     * @param target the repository path of the node the parse is for, or {@code null} for a parse nobody is
     *            waiting on in the repository; when given, the outcome is handed to the registered
     *            {@link io.uhndata.iap.documents.spi.ParseOutcomeHandler}s and the job record is dropped once
     *            one of them has taken it
     * @return the job identifier, which the parse endpoint can be polled with
     * @throws IOException if the job cannot be recorded or queued; the message says which
     */
    @NotNull
    String queue(@NotNull String path, boolean chunk, @Nullable String target) throws IOException;
}
