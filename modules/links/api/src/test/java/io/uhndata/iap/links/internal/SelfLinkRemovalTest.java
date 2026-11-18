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

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.links.api.LinkManager;
import io.uhndata.iap.links.models.ExternalLink;
import io.uhndata.iap.links.models.ExternalLinkDefinition;
import io.uhndata.iap.links.models.InternalLink;
import io.uhndata.iap.links.models.InternalLinkDefinition;
import io.uhndata.iap.links.models.Link;
import io.uhndata.iap.links.models.LinkTypesFixture;
import io.uhndata.iap.links.models.Linkable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Removing a link from content to itself, of a type that is its own backlink. Such a link is its own reverse, so
 * removing the reverse must not remove the link a second time. On an Oak repository, since only a real session
 * refuses to delete a node twice.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SelfLinkRemovalTest
{
    private static final String THING_PATH = "/Things/a";

    private static final String RELATED = "related";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private LinkManagerImpl manager;

    private ResourceResolver resolver;

    private Resource thing;

    @BeforeEach
    void setUp() throws RepositoryException, PersistenceException
    {
        this.context.addModelsForClasses(Content.class, InternalLinkDefinition.class, ExternalLinkDefinition.class,
            InternalLink.class, ExternalLink.class, Linkable.class);
        LinkTypesFixture.registerDefinitionTypes(this.context);
        this.context.registerService(ResourceResolverFactory.class, new TestResolverFactory(
            this.context.resourceResolver(), this.context.getService(ResourceResolverFactory.class)));
        this.manager = this.context.registerInjectActivateService(new LinkManagerImpl());
        this.resolver = this.context.resourceResolver();
        this.createContent(this.resolver.adaptTo(Session.class));
        this.thing = this.resolver.getResource(THING_PATH);
        this.manager.addLink(this.thing, this.thing.adaptTo(Content.class), RELATED, null);
        this.resolver.commit();
    }

    @Test
    void removesTheLinkOnce() throws PersistenceException
    {
        final InternalLink link = this.onlyLink();

        assertTrue(link.remove());
        this.resolver.commit();

        assertFalse(this.thing.getChild(LinkManager.CONTAINER_NAME).hasChildren());
    }

    @Test
    void countsTheLinkOnce() throws PersistenceException
    {
        final Linkable linkable = this.thing.adaptTo(Linkable.class);

        assertEquals(1, linkable.removeLinks(this.thing.adaptTo(Content.class), RELATED, null));
        this.resolver.commit();

        assertFalse(this.thing.getChild(LinkManager.CONTAINER_NAME).hasChildren());
    }

    /** The one link the setup made, which its own type makes its own reverse. */
    private InternalLink onlyLink()
    {
        final Resource link = this.thing.getChild(LinkManager.CONTAINER_NAME).listChildren().next();
        final InternalLink model = (InternalLink) link.adaptTo(Link.class);
        assertEquals(model.getPath(), model.getBacklink().getPath());
        return model;
    }

    private void createContent(final Session session) throws RepositoryException
    {
        final Node related = session.getRootNode().addNode("LinkTypes").addNode(RELATED);
        related.addMixin("mix:referenceable");
        related.setProperty("sling:resourceType", InternalLinkDefinition.RESOURCE_TYPE);
        related.setProperty("backlink", related.getPath());
        final Node thingNode = session.getRootNode().addNode("Things").addNode("a");
        thingNode.addMixin("mix:referenceable");
        thingNode.addNode(LinkManager.CONTAINER_NAME, "link:Links");
        session.save();
    }
}
