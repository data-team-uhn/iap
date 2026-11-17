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
package io.uhndata.iap.links.internal;

import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.DiffObserver;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStateDiff;

/**
 * Removes the reverse of each internal link a commit removed, as the {@code iap-links} service user, if the reverse
 * is still there. It compares each commit with the one before. It handles external changes too: when its queue
 * fills up, Oak merges this instance's own commits into one external change. A reverse already removed, by this
 * observer or by another cluster node's, is not found again.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class BacklinkRemover extends DiffObserver
{
    private final LinksServiceUser serviceUser;

    private final LinkOperations linkOperations;

    /**
     * Basic constructor.
     *
     * @param serviceUser the service user that removes the reverses
     * @param linkOperations the operations that delete a link
     */
    BacklinkRemover(final LinksServiceUser serviceUser, final LinkOperations linkOperations)
    {
        this.serviceUser = serviceUser;
        this.linkOperations = linkOperations;
    }

    @Override
    protected NodeStateDiff getRootDiff(final NodeState before, final NodeState after, final CommitInfo info)
    {
        return new RemovedLinksDiff("/", this::removeReverseOf);
    }

    private void removeReverseOf(final RemovedLink removed)
    {
        // A link is left out of its owner's versions, so removing one needs no checkout
        this.serviceUser.commit(removed.getPath(), "removeBacklink", resolver -> removed.findReverses(resolver)
            .forEach(this.linkOperations::remove));
    }
}
