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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.links.api.LinkManager;
import io.uhndata.iap.links.models.InternalLink;

/**
 * Adds the missing reverse of each committed link whose definition names a backlink, as the {@code iap-links}
 * service user. It completes the pairs whose creator could not add the reverse. A link whose reverse exists, as
 * {@link InternalLink#isReverseOf} tells, is left alone, so the reverse's own creation event adds nothing.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, property = {
    ResourceChangeListener.PATHS + "=/",
    ResourceChangeListener.CHANGES + "=ADDED"
})
public class AutocreateBacklinksListener implements ResourceChangeListener
{
    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private LinkOperations linkOperations;

    private LinksServiceUser serviceUser;

    @Activate
    protected void activate()
    {
        this.serviceUser = new LinksServiceUser(this.resolverFactory, AutocreateBacklinksListener.class);
    }

    @Override
    public void onChange(final List<ResourceChange> changes)
    {
        changes.forEach(this::handleChange);
    }

    private void handleChange(final ResourceChange change)
    {
        final String path = change.getPath();
        // Only a node inside a links container can be a link; skip the rest before logging in
        if (!path.contains("/" + LinkManager.CONTAINER_NAME + "/")) {
            return;
        }
        this.serviceUser.commit(path, "completeBacklink", serviceResolver -> {
            final Resource resource = serviceResolver.getResource(path);
            if (resource != null) {
                this.linkOperations.addBacklink(resource);
            }
        });
    }
}
