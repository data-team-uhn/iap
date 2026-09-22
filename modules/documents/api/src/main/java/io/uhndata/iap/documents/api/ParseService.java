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
     * @param target the repository path of the node the parse is for, or {@code null} for a parse nobody is
     *            waiting on in the repository; when given, the outcome is handed to the registered
     *            {@link io.uhndata.iap.documents.spi.ParseOutcomeHandler}s and the job record is dropped once
     *            one of them has taken it
     * @return the job identifier, which the parse endpoint can be polled with
     * @throws IOException if the job cannot be recorded or queued; the message says which
     */
    @NotNull
    String queue(@NotNull String path, @Nullable String target) throws IOException;

    /**
     * Take a staged document's folder off the shared volume, with everything the parse left beside it.
     *
     * <p>The folder goes, not the outputs one by one: a parse works in a folder of its own, and what else it left
     * there - the staged copy of the upload, a rendition nobody asked for, a {@code .tmp} a killed worker never
     * got to rename - is nobody's to read afterwards. Naming each output would leave whatever was not named.</p>
     *
     * <p>Deleting belongs to whoever owns the volume, which is this service: the path being deleted usually comes
     * from the daemon, and a caller elsewhere has no way to check it is a path this application ever staged. A
     * path outside the shared root is refused rather than followed.</p>
     *
     * @param stagedPath a path inside the folder, as {@link #stage} returned it or as the daemon named an output;
     *            nothing happens when it is {@code null}, blank, or not under the shared root
     */
    void discardStaging(@Nullable String stagedPath);

    /**
     * Whether a path names a file on the shared volume, and so one this application put there.
     *
     * <p>For whoever reads what a parse produced. The paths in an outcome are the daemon's words, over a channel
     * this side does not control the contents of, and reading one means opening whatever file it names as the
     * user the platform runs as. Deleting already refuses a path outside the shared root; reading has to refuse
     * the same ones, or the two disagree about how far the daemon is trusted.</p>
     *
     * <p>Only where the path is, not what is in it: a file under the shared root is one this application staged
     * or a parse wrote beside it, and that is the whole of what this can say.</p>
     *
     * @param path the path to check, as the daemon named it
     * @return {@code true} when it is under the shared root
     */
    boolean isStagedPath(@Nullable String path);
}
