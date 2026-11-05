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
 * Writes the history of the content: for each request somebody made, who made it, what they asked for, and what it
 * did to each resource it affected.
 *
 * <p>
 * The record is written by the commit it describes: {@link #commit} saves the caller's change, and that save records
 * the action with what it changed in each entity. There is no committed change without its record, and no record of a
 * change that was rolled back. The record is written as the committing user, who therefore needs write access under
 * {@code /History}; the store grants it to the {@code iap-history-writers} group.
 * </p>
 *
 * <p>
 * Snapshots come afterwards. A JCR check-in refuses to run while its session has pending changes, and commits by
 * itself, so a snapshot cannot be taken inside the caller's transaction. The sequence is {@link #commit}, take the
 * snapshots, then {@link #completeSnapshots}. Until that last call an action that wants snapshots reads as
 * incomplete, which tells "no snapshot was wanted" from "one was wanted and has not been taken".
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface HistoryRecorder
{
    /**
     * Saves the changes a session holds, recording them as one action. A save that fails records nothing.
     *
     * <p>
     * The action travels to the commit as the session's JCR user data, which it replaces for this save and clears
     * afterwards.
     * </p>
     *
     * @param session the session holding the change, uncommitted
     * @param action the action to record, built with {@link RecordedAction#by}
     * @return the absolute path of the recorded {@code hist:Action}, for {@link #completeSnapshots} and
     *         {@link RecordedAction.Builder#partOf}
     * @throws RepositoryException if the save fails, which leaves the change pending and records nothing
     */
    @NotNull
    String commit(@NotNull Session session, @NotNull RecordedAction action) throws RepositoryException;

    /**
     * Attaches the JCR versions taken of an action's resources, marks the action complete, and saves.
     *
     * <p>
     * Call this only once every snapshot that was wanted has been taken. An action left incomplete records that a
     * snapshot was wanted and did not arrive.
     * </p>
     *
     * @param session a session with write access to the record; any changes pending in it are saved too
     * @param actionPath the path of the action, as {@link #commit} returned it
     * @param snapshots for each resource a version was taken of, its {@code jcr:uuid} mapped to the {@code jcr:uuid}
     *            of that version; empty if the action took none
     * @throws RepositoryException if the record cannot be updated, including when a key names a resource this action
     *             did not affect
     */
    void completeSnapshots(@NotNull Session session, @NotNull String actionPath,
        @NotNull Map<String, String> snapshots) throws RepositoryException;
}
