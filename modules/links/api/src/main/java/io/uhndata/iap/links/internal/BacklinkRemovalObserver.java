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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.jackrabbit.oak.spi.commit.BackgroundObserver;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.Observer;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

/**
 * Removes the reverse of a removed link after the commit, as the {@code iap-links} service user.
 *
 * <p>
 * Giving a link type a backlink is an administrator's decision that every link of the type keeps its reverse. Keeping
 * the pair ignores the access rights of the user who changes one half. The service user adds a reverse its creator
 * could not add, and removes a reverse its remover could not remove.
 * </p>
 *
 * <p>
 * Oak tells every observer about each commit. This one reads each removed link as it was before the commit: its
 * source, destination and type. It removes only that link's reverse, so a link still waiting for its reverse, or of a
 * type that gained a backlink later, is left alone. The work runs on a thread of its own, after the commit.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Observer.class, immediate = true)
public class BacklinkRemovalObserver implements Observer
{
    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private LinkOperations linkOperations;

    private ExecutorService executor;

    private BackgroundObserver background;

    @Activate
    protected void activate()
    {
        this.executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "iap-links-backlink-removal"));
        this.background = new BackgroundObserver(new BacklinkRemover(
            new LinksServiceUser(this.resolverFactory, BacklinkRemovalObserver.class), this.linkOperations),
            this.executor);
    }

    @Deactivate
    protected void deactivate()
    {
        this.background.close();
        this.executor.shutdown();
    }

    @Override
    public void contentChanged(final NodeState root, final CommitInfo info)
    {
        this.background.contentChanged(root, info);
    }
}
