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
package io.uhndata.iap.deletion.api;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * Deletes a resource together with everything its deletion affects. Other resources may reference it or link to it.
 * A deletion first works out every resource it affects, then either refuses with an explanation or deletes them all
 * at once. By default the deleted resources move into the archive at {@value #ARCHIVE_PATH}. From there they can be
 * {@link #restore(Resource) restored}, or {@link #purge(Resource) purged} for good.
 *
 * <p>
 * Every method checks permissions against the session of the resource passed in. The requesting user needs the
 * right to make the change on every resource affected. The {@code iap-deletion} service user makes the changes,
 * since ordinary users cannot write to the archive. {@link #deleteWithin} is the exception, and writes through the
 * resolver of the resource passed in.
 * </p>
 *
 * <p>
 * A resource with the {@code del:Undeletable} mixin cannot be deleted at all, and neither can one that a registered
 * {@link io.uhndata.iap.deletion.spi.DeletionVeto} refuses.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface DeletionService
{
    /** Where deleted resources are kept until they are restored or purged. */
    String ARCHIVE_PATH = "/Archive";

    /** The node type of the {@value #ARCHIVE_PATH} root, and of the buckets under it. */
    String ARCHIVE_NODETYPE = "del:Archive";

    /** The node type of an archive entry. Each archiving deletion creates one. */
    String ENTRY_NODETYPE = "del:ArchiveEntry";

    /** The node type that wraps each archived subtree and records where it came from. */
    String ITEM_NODETYPE = "del:DeletedItem";

    /** The mixin marking a resource that must never be deleted. */
    String UNDELETABLE_MIXIN = "del:Undeletable";

    /** The archive entry's property holding the user id of the user who requested the deletion. */
    String DELETED_BY_PROPERTY = "deletedBy";

    /** The archive entry's property holding the path whose deletion was requested. */
    String REQUESTED_PATH_PROPERTY = "requestedPath";

    /** The archived item's property holding the path its subtree was moved from. */
    String ORIGINAL_PATH_PROPERTY = "originalPath";

    /**
     * What deleting a resource would do. Nothing is changed.
     *
     * @param item the resource to delete, read through the requesting user's resolver
     * @param options how the deletion would be made
     * @return what the deletion would remove, and what would block it
     * @throws DeletionException if the impact cannot be worked out, such as when the repository is unavailable
     * @throws IllegalArgumentException if the resource is the repository root, is in the archive, or has no JCR node
     */
    @NotNull
    DeletionImpact analyze(@NotNull Resource item, @NotNull DeletionOptions options);

    /**
     * Delete a resource and everything its deletion affects, and commit. By default all of it moves into a new
     * archive entry. A permanent deletion removes it for good. Nothing is deleted if a guard vetoes any affected
     * resource, or if the requesting user may not remove one of them. Nothing is deleted either if other resources
     * reference it and the options do not allow deleting them too.
     *
     * @param item the resource to delete, read through the requesting user's resolver
     * @param options whether to delete the referencing resources too, and whether to skip the archive
     * @return the outcome; only {@link DeletionResult.Status#ARCHIVED} and {@link DeletionResult.Status#DELETED}
     *         mean that something changed
     * @throws DeletionException if the deletion fails for a reason other than a refusal, such as when the
     *             repository is unavailable
     * @throws IllegalArgumentException if the resource is the repository root, is in the archive, or has no JCR node
     */
    @NotNull
    DeletionResult delete(@NotNull Resource item, @NotNull DeletionOptions options);

    /**
     * Delete a resource as {@link #delete} does, inside the caller's transaction. The changes go through the
     * resolver the item came from, which needs write access to the archive. Nothing is committed: the caller
     * commits or reverts the deletion with the rest of its changes.
     *
     * <p>
     * Moving a node out of a checked-in versionable ancestor needs that ancestor checked out. A check-out takes
     * effect at once, but a check-in refuses pending changes. The result therefore
     * {@link DeletionResult#getCheckedOut lists} the nodes for the caller to check back in after its commit.
     * </p>
     *
     * @param item the resource to delete, whose resolver holds the caller's transaction
     * @param options how to delete, as for {@link #delete}
     * @return the outcome, as for {@link #delete}, with the nodes left checked out
     * @throws DeletionException if the deletion fails for a reason other than a refusal, such as when the
     *             repository is unavailable
     * @throws IllegalArgumentException if the resource is the repository root, is in the archive, or has no JCR node
     * @since 0.1.0
     */
    @NotNull
    DeletionResult deleteWithin(@NotNull Resource item, @NotNull DeletionOptions options);

    /**
     * Move the contents of an archive entry back to their original paths, remove the emptied entry, and commit.
     * Either every item goes back or nothing changes. An item cannot go back if its original parent is gone, if
     * another resource is at its original path, or if the requesting user may not create it there. Each such
     * conflict is reported. Links removed by the deletion are not recreated.
     *
     * @param archiveEntry a {@code del:ArchiveEntry} resource, read through the requesting user's resolver
     * @return the outcome, with the restored paths on success or the conflicts on refusal
     * @throws DeletionException if the restore fails for a reason other than a conflict, such as when the
     *             repository is unavailable
     * @throws IllegalArgumentException if the resource is not an archive entry
     */
    @NotNull
    RestoreResult restore(@NotNull Resource archiveEntry);

    /**
     * Permanently delete an archive entry and everything in it, and commit. The guards are asked again, about the
     * entry and every node in it. A veto blocks the purge, such as one for an archived resource that has since
     * gained the {@code del:Undeletable} mixin.
     *
     * @param archiveEntry a {@code del:ArchiveEntry} resource, read through the requesting user's resolver
     * @return the outcome; only {@link DeletionResult.Status#DELETED} means the entry was removed
     * @throws DeletionException if the purge fails for a reason other than a veto, such as when the repository is
     *             unavailable
     * @throws IllegalArgumentException if the resource is not an archive entry
     */
    @NotNull
    DeletionResult purge(@NotNull Resource archiveEntry);

    /**
     * What would block restoring an archive entry now. Nothing is restored. This is the check {@link #restore}
     * makes before it moves anything. Another deletion or restore can occupy a path afterwards, so
     * {@link #restore} checks again.
     *
     * @param archiveEntry a {@code del:ArchiveEntry} resource, read through the requesting user's resolver
     * @return every conflict that would block a restore; empty if a restore would succeed
     * @throws DeletionException if the check fails, such as when the repository is unavailable
     * @throws IllegalArgumentException if the resource is not an archive entry
     * @since 0.1.0
     */
    @NotNull
    List<RestoreConflict> checkRestore(@NotNull Resource archiveEntry);

    /**
     * Which guards would refuse to purge an archive entry now. Nothing is purged. This asks the guards what
     * {@link #purge} asks them before it removes anything. An answer can change later, such as when a retention
     * period ends, so {@link #purge} asks again.
     *
     * @param archiveEntry a {@code del:ArchiveEntry} resource, read through the requesting user's resolver
     * @return every veto that would block a purge; empty if a purge would succeed
     * @throws DeletionException if the check fails, such as when the repository is unavailable
     * @throws IllegalArgumentException if the resource is not an archive entry
     * @since 0.1.0
     */
    @NotNull
    List<Veto> checkPurge(@NotNull Resource archiveEntry);
}
