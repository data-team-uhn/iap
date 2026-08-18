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
package io.uhndata.iap.deletion.spi;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A guard protecting resources from deletion. Before anything is deleted, every registered veto is asked about
 * every impacted resource, including the descendants of the deleted ones; a single veto anywhere blocks the whole
 * operation. Vetoes must be fast, side-effect free, and must not hold on to the passed node. A veto that throws is
 * counted as a veto — when a guard cannot decide, the data stays.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface DeletionVeto
{
    /**
     * A short name identifying this veto in reports, e.g. {@code undeletable}.
     *
     * @return a stable, human-readable identifier
     */
    @NotNull
    String getName();

    /**
     * Decide whether a resource may be deleted.
     *
     * <p>
     * The node is read through the deletion service's own privileged session, so it is visible here whether or
     * not the requester can see it; {@code requester} answers questions about <em>who</em> is deleting, not about
     * what they can read. A guard that means "only these people may do this" belongs here rather than in access
     * control precisely because the two sessions differ: the requesting user is authorized separately, node by
     * node, before any guard is asked.
     * </p>
     *
     * @param node a node that the examined deletion would remove from its current location, in a privileged
     *            session
     * @param mode the kind of deletion being examined
     * @param requester the session of the user who asked for the deletion, for identity
     *            ({@code getUserID()}) and what it acts as ({@code JackrabbitSession.getBoundPrincipals()}); never
     *            written to
     * @return a human-readable reason why the deletion must not happen, or {@code null} to allow it
     * @throws RepositoryException if the decision cannot be made; treated as a veto
     */
    @Nullable
    String veto(@NotNull Node node, @NotNull DeletionMode mode, @NotNull Session requester)
        throws RepositoryException;
}
