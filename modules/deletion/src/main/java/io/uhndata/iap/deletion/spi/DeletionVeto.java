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
 * A guard that can refuse a deletion. Before anything is deleted, every registered guard is asked about every node
 * the deletion would remove, descendants included. A guard that {@link #judgesWholeOperation() judges the whole
 * deletion} is asked once, about the requested resource. A single veto blocks the whole deletion.
 *
 * <p>
 * A guard must be fast, change nothing, and not keep the node it is given. A guard that throws counts as a veto, so
 * the data stays when a guard cannot decide.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface DeletionVeto
{
    /**
     * The name that identifies this guard in the objections it raises.
     *
     * @return a short name that does not change, such as {@code undeletable}
     */
    @NotNull
    String getName();

    /**
     * Whether a node may be deleted.
     *
     * <p>
     * The node is read through the {@code iap-deletion} service user's session, so the guard sees it whether or not
     * the requesting user can. The requesting user's own rights are checked separately. {@code requester} says who
     * is deleting, not what they can read.
     * </p>
     *
     * @param node a node the deletion would remove from where it is, read through the service user's session
     * @param mode the kind of deletion asked about
     * @param requester the session of the user who asked for the deletion. {@code getUserID()} says who they are,
     *            and {@code JackrabbitSession.getBoundPrincipals()} names their groups and roles. Never write
     *            through it.
     * @return why the node must not be deleted, in a sentence for the requesting user; {@code null} to allow it
     * @throws RepositoryException if the guard cannot decide, which counts as a veto
     */
    @Nullable
    String veto(@NotNull Node node, @NotNull DeletionMode mode, @NotNull Session requester)
        throws RepositoryException;

    /**
     * Whether this guard judges the deletion as a whole, rather than each node on its own.
     *
     * <p>
     * A guard that asks "is this node protected?" answers about each node, and keeps the default. It sees every
     * node, and its objections name the nodes it refused. A guard for a policy such as "nobody may delete anything
     * for good" gives the same answer for every node. Asked per node, it would raise one identical objection per
     * node of the subtree. It returns {@code true}, and is asked only about the requested resource.
     * </p>
     *
     * <p>
     * As a rule, a guard that reads anything off its {@code node} answers about each node, however few it refuses.
     * That includes a guard that only checks the node type.
     * </p>
     *
     * <p>
     * A guard whose answer depends on what is deleted together returns {@code true}, though it reads its node. "Nothing
     * that stays may depend on anything that goes" is such a rule. A dependency inside the deleted subtree goes with
     * it, so no single node can answer. The guard reads the requested resource's subtree itself. It is never asked
     * about the other resources a recursive deletion takes along.
     * </p>
     *
     * @return {@code true} to be asked once, about the requested resource; {@code false}, the default, to be asked
     *         about every node the deletion removes
     */
    default boolean judgesWholeOperation()
    {
        return false;
    }
}
