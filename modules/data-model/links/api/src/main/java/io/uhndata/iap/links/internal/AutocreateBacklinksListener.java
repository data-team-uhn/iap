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
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.links.api.LinkManager;
import io.uhndata.iap.links.models.InternalLink;

/**
 * Completes backlink pairs after links are committed. Links whose reverse the creating user could not write —
 * including links created inside commit hooks, where no second commit is possible — are picked up here and
 * completed with the links service user. Completed pairs are recognized from the stored data itself (see
 * {@link InternalLink#isReverseOf}), so processing the reverse link's own creation event finds the pair already
 * complete and stops, instead of ping-ponging new links between the two resources.
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
    private static final Logger LOGGER = LoggerFactory.getLogger(AutocreateBacklinksListener.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private LinkOperations linkOperations;

    @Override
    public void onChange(final List<ResourceChange> changes)
    {
        changes.forEach(this::handleChange);
    }

    private void handleChange(final ResourceChange change)
    {
        final String path = change.getPath();
        // Quick filtering to avoid creating a new session for unrelated changes
        if (!path.contains("/" + LinkManager.CONTAINER_NAME + "/")) {
            return;
        }
        try (ResourceResolver serviceResolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, LinkManagerImpl.SUBSERVICE))) {
            final Resource resource = serviceResolver.getResource(path);
            if (resource != null) {
                this.linkOperations.addBacklink(resource);
                if (serviceResolver.hasChanges()) {
                    serviceResolver.commit();
                }
            }
        } catch (final LoginException e) {
            LOGGER.warn("Failed to get a service session for completing backlinks: {}", e.getMessage(), e);
        } catch (final PersistenceException e) {
            LOGGER.warn("Failed to complete the backlink for {}: {}", path, e.getMessage(), e);
        }
    }
}
