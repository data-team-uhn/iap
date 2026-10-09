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
package io.uhndata.iap.history.api;

import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.jetbrains.annotations.NotNull;

/**
 * Writes the record of what happened: what was asked for, and what it did to each resource.
 *
 * <p>
 * The record shares the caller's transaction. {@link #record} leaves the new nodes pending in the session it is
 * given, and the caller commits them with the change itself. There is no committed change without its record, and no
 * record of a change that was rolled back.
 * </p>
 *
 * <p>
 * Snapshots come afterwards. A JCR check-in refuses to run while its session has pending changes, and commits by
 * itself, so a snapshot cannot be taken inside the caller's transaction. The sequence is {@link #record}, commit,
 * take the snapshots, then {@link #completeSnapshots}. Until that last call the action reads as incomplete, which
 * separates "no snapshot was wanted" from "one was wanted and has not arrived".
 * </p>
 *
 * <p>
 * Whoever calls this needs write access under {@code /History}, since the writing happens in their session. The store
 * grants that to the {@code iap-history-writers} group; a module that records joins it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface HistoryRecorder
{
    /**
     * Records an action and its effects, leaving them pending in the caller's session.
     *
     * <p>The caller must commit. Nothing here saves the session.</p>
     *
     * @param session the session the change being recorded is being made in
     * @param action what was asked for, and what it did
     * @return the path of the recorded action, for use with {@link #completeSnapshots}
     * @throws RepositoryException if the record cannot be written, or if the action names the same resource twice
     */
    @NotNull
    String record(@NotNull Session session, @NotNull RecordedAction action) throws RepositoryException;

    /**
     * Attaches the snapshots an action took and marks it finished, in a commit of its own.
     *
     * <p>
     * Call this only once every snapshot that was wanted has been taken. An action left incomplete records that a
     * snapshot was wanted and did not arrive.
     * </p>
     *
     * @param session a session with write access to the record; its pending changes, if any, are saved along with this
     * @param actionPath the path {@link #record} returned
     * @param snapshots the identifier of the version taken for each affected resource, keyed by the resource's own
     *            identifier
     * @throws RepositoryException if the record cannot be updated, including when a key names a resource this action
     *             did not affect
     */
    void completeSnapshots(@NotNull Session session, @NotNull String actionPath,
        @NotNull Map<String, String> snapshots) throws RepositoryException;
}
