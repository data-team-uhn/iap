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
 * Safe deletion of resources. Deleting a resource is more than removing one node: other resources may reference it,
 * link to it, or depend on it, so a deletion first resolves the complete set of impacted resources, then either
 * refuses with an explanation, or carries the whole set over in one operation. By default deleted resources are not
 * removed but moved into the archive at {@value #ARCHIVE_PATH}, where they can later be
 * {@link #restore(Resource) restored} or definitively {@link #purge(Resource) purged}.
 *
 * <p>
 * All methods authorize against the session of the resource passed in — the requesting user needs the repository
 * permissions for the change on every impacted resource — while the actual changes are performed by a privileged
 * service session, since regular users cannot write to the archive. Resources bearing the {@code del:Undeletable}
 * mixin, or vetoed by any registered {@link io.uhndata.iap.deletion.spi.DeletionVeto}, cannot be deleted at all.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface DeletionService
{
    /** The repository location where deleted resources are kept until restored or purged. */
    String ARCHIVE_PATH = "/Archive";

    /** The node type of the {@value #ARCHIVE_PATH} root. */
    String ARCHIVE_NODETYPE = "del:Archive";

    /** The node type of an archive entry, one per deletion operation. */
    String ENTRY_NODETYPE = "del:ArchiveEntry";

    /** The node type of the wrapper around each archived subtree, recording where it came from. */
    String ITEM_NODETYPE = "del:DeletedItem";

    /** The mixin marking resources that must never be deleted. */
    String UNDELETABLE_MIXIN = "del:Undeletable";

    /** The name of the archive entry property recording the user who requested the deletion. */
    String DELETED_BY_PROPERTY = "deletedBy";

    /** The name of the archive entry property recording the path whose deletion was requested. */
    String REQUESTED_PATH_PROPERTY = "requestedPath";

    /** The name of the archived item property recording the path its subtree was moved from. */
    String ORIGINAL_PATH_PROPERTY = "originalPath";

    /**
     * Compute what deleting a resource would do, without changing anything.
     *
     * @param item the resource to examine
     * @param options the deletion options to examine against
     * @return the complete impact of the hypothetical deletion
     * @throws DeletionException if the impact cannot be computed, e.g. the repository is unavailable
     */
    @NotNull
    DeletionImpact analyze(@NotNull Resource item, @NotNull DeletionOptions options);

    /**
     * Delete a resource, together with everything its deletion impacts: by default the whole set is moved into a
     * new archive entry, or, when the options ask for a permanent deletion, removed from the repository for good.
     * The operation is refused as a whole if any impacted resource is vetoed, if resources other than links would
     * be impacted and the options don't allow cascading, or if the requesting user lacks the permission to remove
     * any impacted resource.
     *
     * @param item the resource to delete
     * @param options how to delete: whether to cascade over referencing resources, and whether to skip the archive
     * @return the outcome; only the statuses {@link DeletionResult.Status#ARCHIVED} and
     *         {@link DeletionResult.Status#DELETED} mean that something was changed
     * @throws DeletionException if the deletion fails for a non-business reason, e.g. the repository is unavailable
     */
    @NotNull
    DeletionResult delete(@NotNull Resource item, @NotNull DeletionOptions options);

    /**
     * Move the contents of an archive entry back to their recorded original locations, and remove the emptied
     * entry. The restore is all-or-nothing: if any archived item cannot be restored — its original parent is gone,
     * its original path is occupied, or the requesting user may not create it — nothing is changed and all the
     * conflicts are reported. Links removed by the original deletion are not recreated.
     *
     * @param archiveEntry an {@code del:ArchiveEntry} resource
     * @return the outcome, including the restored paths on success or the conflicts on refusal
     * @throws DeletionException if the restore fails for a non-business reason, e.g. the repository is unavailable
     * @throws IllegalArgumentException if the resource is not an archive entry
     */
    @NotNull
    RestoreResult restore(@NotNull Resource archiveEntry);

    /**
     * Permanently delete an archive entry and everything in it. Vetoes are consulted again: an archived resource
     * that acquired the {@code del:Undeletable} mixin, or is otherwise vetoed for purging, blocks the purge.
     *
     * @param archiveEntry an {@code del:ArchiveEntry} resource
     * @return the outcome; only the status {@link DeletionResult.Status#DELETED} means the entry was removed
     * @throws DeletionException if the purge fails for a non-business reason, e.g. the repository is unavailable
     * @throws IllegalArgumentException if the resource is not an archive entry
     */
    @NotNull
    DeletionResult purge(@NotNull Resource archiveEntry);

    /**
     * Report what would stand in the way of restoring an archive entry, without restoring it. The same evaluation
     * {@link #restore} performs before it moves anything, so an empty result means a restore requested now would
     * succeed — "now" being the operative word: another deletion or a concurrent restore can occupy a path in the
     * meantime, which is why the restore itself checks again rather than trusting this.
     *
     * @param archiveEntry an {@code del:ArchiveEntry} resource
     * @return every conflict blocking a restore, empty if there are none
     * @throws DeletionException if the check fails for a non-business reason, e.g. the repository is unavailable
     * @throws IllegalArgumentException if the resource is not an archive entry
     * @since 0.1.0
     */
    @NotNull
    List<RestoreConflict> checkRestore(@NotNull Resource archiveEntry);

    /**
     * Report which guards would refuse to purge an archive entry, without purging it. The same sweep {@link #purge}
     * performs before it destroys anything, with the same caveat: a guard's answer can change — a retention floor
     * expires, a mixin is added — so the purge consults them again.
     *
     * @param archiveEntry an {@code del:ArchiveEntry} resource
     * @return every veto blocking a purge, empty if there are none
     * @throws DeletionException if the check fails for a non-business reason, e.g. the repository is unavailable
     * @throws IllegalArgumentException if the resource is not an archive entry
     * @since 0.1.0
     */
    @NotNull
    List<Veto> checkPurge(@NotNull Resource archiveEntry);
}
