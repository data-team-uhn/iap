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

import java.util.function.Consumer;

import org.apache.jackrabbit.oak.spi.state.DefaultNodeStateDiff;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import io.uhndata.iap.links.api.LinkManager;

/**
 * Finds the internal links a commit removed while their source stayed: a link removed on its own, and each link of a
 * removed links container. It does not look inside other removed nodes. Content removed with its links takes their
 * source with it, and what happens to the links pointing at it is up to their definitions' {@code onDelete}.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class RemovedLinksDiff extends DefaultNodeStateDiff
{
    private final String path;

    private final Consumer<RemovedLink> removedLinks;

    /**
     * Basic constructor.
     *
     * @param path the path of the node this diff compares, {@code /} for the root
     * @param removedLinks receives each removed link
     */
    RemovedLinksDiff(final String path, final Consumer<RemovedLink> removedLinks)
    {
        this.path = path;
        this.removedLinks = removedLinks;
    }

    @Override
    public boolean childNodeChanged(final String name, final NodeState before, final NodeState after)
    {
        return after.compareAgainstBaseState(before, new RemovedLinksDiff(this.childPath(name), this.removedLinks));
    }

    @Override
    public boolean childNodeDeleted(final String name, final NodeState before)
    {
        final String childPath = this.childPath(name);
        if (LinkManager.CONTAINER_NAME.equals(name)) {
            before.getChildNodeEntries()
                .forEach(entry -> this.reportIfLink(childPath + "/" + entry.getName(), entry.getNodeState()));
        } else {
            this.reportIfLink(childPath, before);
        }
        return true;
    }

    private void reportIfLink(final String path, final NodeState node)
    {
        if (RemovedLink.isLink(node)) {
            this.removedLinks.accept(new RemovedLink(path, node));
        }
    }

    private String childPath(final String name)
    {
        return "/".equals(this.path) ? "/" + name : this.path + "/" + name;
    }
}
