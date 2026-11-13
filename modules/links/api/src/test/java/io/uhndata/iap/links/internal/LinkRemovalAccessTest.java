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

import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
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
import io.uhndata.iap.links.models.LinkTypesFixture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Removing a link from a user's session, on an Oak repository that enforces the user's access rights: the reverse
 * link goes too only where the user may remove it, so that the user's save never fails over it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LinkRemovalAccessTest
{
    private static final String EDITOR = "editor";

    private static final String SOURCE_PATH = "/Things/a";

    private static final String DESTINATION_PATH = "/Things/b";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private LinkManagerImpl manager;

    private Session adminSession;

    private String linkPath;

    private String backlinkPath;

    @BeforeEach
    void setUp() throws RepositoryException, PersistenceException
    {
        this.context.addModelsForClasses(Content.class, InternalLinkDefinition.class, ExternalLinkDefinition.class,
            InternalLink.class, ExternalLink.class);
        LinkTypesFixture.registerDefinitionTypes(this.context);
        this.context.registerService(ResourceResolverFactory.class, new TestResolverFactory(
            this.context.resourceResolver(), this.context.getService(ResourceResolverFactory.class)));
        this.manager = this.context.registerInjectActivateService(new LinkManagerImpl());
        this.adminSession = this.context.resourceResolver().adaptTo(Session.class);
        this.createReferencesDefinitions();
        this.createLinkedPair();
    }

    @Test
    void leavesTheReverseItMayNotRemove() throws RepositoryException, LoginException
    {
        this.createEditor(SOURCE_PATH);

        try (ResourceResolver editor = this.loginEditor()) {
            assertTrue(this.manager.remove(editor.getResource(this.linkPath), true));

            assertDoesNotThrow(editor::commit);
        }

        this.adminSession.refresh(false);
        assertNull(this.context.resourceResolver().getResource(this.linkPath));
        assertNotNull(this.context.resourceResolver().getResource(this.backlinkPath));
    }

    @Test
    void removesTheReverseItMayRemove() throws RepositoryException, LoginException, PersistenceException
    {
        this.createEditor("/Things");

        try (ResourceResolver editor = this.loginEditor()) {
            assertTrue(this.manager.remove(editor.getResource(this.linkPath), true));

            editor.commit();
        }

        this.adminSession.refresh(false);
        assertNull(this.context.resourceResolver().getResource(this.linkPath));
        assertNull(this.context.resourceResolver().getResource(this.backlinkPath));
    }

    private void createReferencesDefinitions() throws RepositoryException
    {
        final Node types = this.adminSession.getRootNode().addNode("LinkTypes");
        final Node references = this.referenceable(types.addNode("references"));
        final Node referencedBy = this.referenceable(types.addNode("referencedBy"));
        references.setProperty("sling:resourceType", InternalLinkDefinition.RESOURCE_TYPE);
        references.setProperty("backlink", referencedBy.getPath());
        referencedBy.setProperty("sling:resourceType", InternalLinkDefinition.RESOURCE_TYPE);
        referencedBy.setProperty("backlink", references.getPath());
        referencedBy.setProperty("backlinkOnly", true);
        this.adminSession.save();
    }

    /** Links the source to the destination as the administrator, who adds the reverse in the same save. */
    private void createLinkedPair() throws RepositoryException, PersistenceException
    {
        final Node things = this.adminSession.getRootNode().addNode("Things");
        for (final Node thing : new Node[] { things.addNode("a"), things.addNode("b") }) {
            this.referenceable(thing).addNode(LinkManager.CONTAINER_NAME, "link:Links");
        }
        this.adminSession.save();
        final ResourceResolver admin = this.context.resourceResolver();
        final InternalLink link = this.manager.addLink(admin.getResource(SOURCE_PATH),
            admin.getResource(DESTINATION_PATH).adaptTo(Content.class), "references", null);
        admin.commit();
        this.linkPath = link.getPath();
        this.backlinkPath = link.getBacklink().getPath();
    }

    private Node referenceable(final Node node) throws RepositoryException
    {
        node.addMixin("mix:referenceable");
        return node;
    }

    /**
     * Creates a user who may read everything and change everything under one path.
     *
     * @param writablePath the subtree the user may change
     */
    private void createEditor(final String writablePath) throws RepositoryException
    {
        final User editor = ((JackrabbitSession) this.adminSession).getUserManager().createUser(EDITOR, EDITOR);
        AccessControlUtils.addAccessControlEntry(this.adminSession, "/", editor.getPrincipal(),
            new String[] { Privilege.JCR_READ }, true);
        AccessControlUtils.addAccessControlEntry(this.adminSession, writablePath, editor.getPrincipal(),
            new String[] { Privilege.JCR_ALL }, true);
        this.adminSession.save();
    }

    private ResourceResolver loginEditor() throws LoginException
    {
        return this.context.getService(ResourceResolverFactory.class).getResourceResolver(Map.of(
            ResourceResolverFactory.USER, EDITOR,
            ResourceResolverFactory.PASSWORD, EDITOR.toCharArray()));
    }
}
