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
 * <b>The record shares the caller's transaction, on purpose.</b> {@link #record} leaves the new nodes pending in the
 * session it is given and the caller commits them along with the change itself, so there can be no committed change
 * without its record and no record of a change that was rolled back. This is the opposite of how error recording
 * works, and deliberately: a failure has to be recorded <em>because</em> the caller's transaction is being abandoned,
 * so that writes in a session of its own, while history must be abandoned with it.
 * </p>
 *
 * <p>
 * <b>Snapshots come afterwards, and cannot come with it.</b> A JCR check-in refuses to run while its session has
 * pending changes, and commits by itself — so taking a snapshot inside the caller's transaction is both impossible and,
 * where it would work, wrong, since it would flush half of the caller's work early. The sequence is therefore: call
 * {@link #record}, commit, take the snapshots, then call {@link #completeSnapshots}. Until that last call the action
 * reads as incomplete, which is what distinguishes "no snapshot was wanted here" from "one was wanted and has not
 * arrived".
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
     * <p>
     * The caller must commit. Nothing here saves the session — that is the whole point of this method: the record and
     * the change it describes reach the repository together or not at all.
     * </p>
     *
     * @param session the session the change being recorded is being made in
     * @param action what was asked for, and what it did
     * @return the path of the recorded action, for use with {@link #completeSnapshots}
     * @throws RepositoryException if the record cannot be written, including when the action names the same resource
     *             twice — the store refuses that rather than silently keeping one of them
     */
    @NotNull
    String record(@NotNull Session session, @NotNull RecordedAction action) throws RepositoryException;

    /**
     * Attaches the snapshots an action took and marks it finished, in a commit of its own.
     *
     * <p>
     * Call this only once every snapshot that was wanted has been taken. Leaving an action incomplete is the honest
     * record of a snapshot that was wanted and failed, and is worth more than a record claiming the action finished
     * what it did not.
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
