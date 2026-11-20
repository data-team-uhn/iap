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

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.Privilege;
import javax.jcr.version.VersionManager;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.osgi.framework.Constants;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.links.api.LinkManager;
import io.uhndata.iap.links.models.ExternalLink;
import io.uhndata.iap.links.models.ExternalLinkDefinition;
import io.uhndata.iap.links.models.InternalLink;
import io.uhndata.iap.links.models.InternalLinkDefinition;
import io.uhndata.iap.links.models.LinkTypesFixture;
import io.uhndata.iap.utils.testing.TestResolverFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BacklinkRemover} and {@link BacklinkRemovalObserver}, on an Oak repository that enforces the
 * users' access rights. The editor may change only {@code /Things/a}, so a reverse elsewhere outlives the editor's
 * save.
 *
 * <p>
 * The mock repository does not expose its node store. Each test copies the links a commit removes from the
 * repository into a root built with Oak's memory node builder, and hands the observer that root before and after the
 * removal.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class BacklinkRemoverTest
{
    private static final String EDITOR = "editor";

    private static final String AUTHOR = "author";

    private static final String THINGS = "/Things";

    private static final String A = "/Things/a";

    private static final String B = "/Things/b";

    private static final String C = "/Things/c";

    private static final String REFERENCES = "references";

    private static final String MENTIONS = "mentions";

    private static final String BACKLINK = "backlink";

    private static final String REFERENCE = "reference";

    private static final String LABEL = "label";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private LinkManagerImpl manager;

    /** The mock repository's own factory, which users log in through. */
    private ResourceResolverFactory repository;

    private BacklinkRemover remover;

    private Session adminSession;

    /** A link from {@code a} to {@code b}, whose reverse under {@code b} the editor may not remove. */
    private String linkPath;

    private String reversePath;

    @BeforeEach
    void setUp() throws RepositoryException, PersistenceException
    {
        this.context.addModelsForClasses(Content.class, InternalLinkDefinition.class, ExternalLinkDefinition.class,
            InternalLink.class, ExternalLink.class);
        LinkTypesFixture.registerDefinitionTypes(this.context);
        this.repository = this.context.getService(ResourceResolverFactory.class);
        final ResourceResolverFactory factory = TestResolverFactory
            .answeredBy(this.repository::getServiceResourceResolver)
            .withSubservice(LinkManagerImpl.DEFINITIONS_SUBSERVICE, this.context.resourceResolver());
        this.context.registerService(ResourceResolverFactory.class, factory);
        this.manager = this.context.registerInjectActivateService(new LinkManagerImpl());
        this.remover = new BacklinkRemover(new LinksServiceUser(factory, BacklinkRemoverTest.class),
            this.manager);
        this.adminSession = this.context.resourceResolver().adaptTo(Session.class);
        this.createDefinitions();
        this.createThings("a", "b", "c");
        final InternalLink link = this.link(A, B, REFERENCES, null);
        this.linkPath = link.getPath();
        this.reversePath = link.getBacklink().getPath();
        this.createUser(EDITOR, A);
    }

    @Test
    void removesTheReverseTheEditorMayNotRemove() throws Exception
    {
        this.editorRemovesTheLink();

        assertFalse(this.exists(this.reversePath));
    }

    @Test
    void removesTheLinkWhoseReverseWasRemoved() throws Exception
    {
        final InternalLink citation = this.link(C, A, "cites", null);
        final String reverse = citation.getBacklink().getPath();
        final NodeState before = this.rootWith(reverse);

        this.removeAs(EDITOR, reverse);
        this.observe(before, without(before, reverse));

        assertFalse(this.exists(citation.getPath()));
    }

    @Test
    void keepsALinkWaitingForItsReverse() throws Exception
    {
        final String waiting = this.authorLinksCToA();

        this.editorRemovesTheLink();

        assertTrue(this.exists(waiting));
    }

    @Test
    void keepsALinkWhoseReverseCouldNotBeAdded() throws Exception
    {
        final String unpaired = this.authorLinksCToA();
        this.addBacklinkWithoutAServiceUser(unpaired);

        this.editorRemovesTheLink();

        assertTrue(this.exists(unpaired));
    }

    @Test
    void keepsALinkOfATypeThatGainedABacklinkLater() throws Exception
    {
        final String mention = this.link(C, A, MENTIONS, null).getPath();
        this.adminSession.getNode("/LinkTypes/" + MENTIONS).setProperty(BACKLINK, "/LinkTypes/" + MENTIONS);
        this.adminSession.save();

        this.editorRemovesTheLink();

        assertTrue(this.exists(mention));
    }

    @Test
    void keepsAReverseThatAnotherLinkStillPairsWith() throws Exception
    {
        // A second link between the same ends, with another label, shares the reverse
        this.link(A, B, REFERENCES, "second");

        this.editorRemovesTheLink();

        assertTrue(this.exists(this.reversePath));
    }

    @Test
    void keepsTheDestinationsLinksOfOtherTypes() throws Exception
    {
        final String mention = this.link(B, A, MENTIONS, null).getPath();
        final ResourceResolver admin = this.context.resourceResolver();
        final String external = this.manager.addExternalLink(admin.getResource(B), "identifier", "12345", null)
            .getPath();
        admin.commit();

        this.editorRemovesTheLink();

        assertTrue(this.exists(mention));
        assertTrue(this.exists(external));
    }

    @Test
    void keepsTheDestinationsLinksToOtherContent() throws Exception
    {
        // A link to c that has lost its own counterpart, so only its destination tells it apart
        final InternalLink cToB = this.link(C, B, REFERENCES, null);
        final String toC = cToB.getBacklink().getPath();
        this.adminSession.getNode(cToB.getPath()).remove();
        this.adminSession.save();

        this.editorRemovesTheLink();

        assertTrue(this.exists(toC));
    }

    @Test
    void findsNothingLeftAfterItsOwnRemoval() throws Exception
    {
        final String otherReverse = this.link(C, A, REFERENCES, null).getBacklink().getPath();
        final NodeState before = this.rootWith(this.linkPath, this.reversePath);
        final NodeState withoutLink = without(before, this.linkPath);
        this.removeAs(EDITOR, this.linkPath);
        this.observe(before, withoutLink);

        this.remover.contentChanged(without(withoutLink, this.reversePath), CommitInfo.EMPTY);

        assertFalse(this.exists(this.reversePath));
        assertTrue(this.exists(otherReverse));
    }

    @Test
    void hasNothingToDoWhenBothHalvesGoInOneSave() throws Exception
    {
        final String otherReverse = this.link(C, A, REFERENCES, null).getBacklink().getPath();
        this.allowToChange(EDITOR, THINGS);
        final NodeState before = this.rootWith(this.linkPath, this.reversePath);

        this.removeAs(EDITOR, this.linkPath);
        this.observe(before, without(without(before, this.linkPath), this.reversePath));

        assertFalse(this.exists(this.reversePath));
        assertTrue(this.exists(otherReverse));
    }

    @Test
    void removesTheReversesOfARemovedContainer() throws Exception
    {
        final String container = A + "/" + LinkManager.CONTAINER_NAME;
        final NodeState before = this.rootWith(this.linkPath);
        try (ResourceResolver editor = this.login(EDITOR)) {
            editor.delete(editor.getResource(container));
            editor.commit();
        }

        this.observe(before, without(before, container));

        assertFalse(this.exists(this.reversePath));
    }

    @Test
    void leavesTheLinksOfRemovedContentToTheirDefinitions() throws Exception
    {
        final NodeState before = this.rootWith(this.linkPath);
        this.removeAs(EDITOR, this.linkPath);

        this.observe(before, without(before, A));

        assertTrue(this.exists(this.reversePath));
    }

    @Test
    void ignoresRemovedNodesThatAreNotLinks()
    {
        final BacklinkRemover withoutServiceUser = new BacklinkRemover(
            new LinksServiceUser(this.definitionsOnly(), BacklinkRemoverTest.class), this.manager);
        final NodeBuilder root = EmptyNodeState.EMPTY_NODE.builder();
        builderAt(root, A + "/" + LinkManager.CONTAINER_NAME + "/external")
            .setProperty("jcr:primaryType", "link:ExternalLink", Type.NAME);
        builderAt(root, B).setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        final NodeState before = root.getNodeState();
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);

        try {
            withoutServiceUser.contentChanged(before, CommitInfo.EMPTY);
            withoutServiceUser.contentChanged(
                without(without(before, A + "/" + LinkManager.CONTAINER_NAME), B), CommitInfo.EMPTY);

            // Each attempt to remove a reverse would have failed to log in, and recorded it
            Mockito.verifyNoInteractions(recorder);
        } finally {
            ErrorLogger.unsetService(recorder);
        }
    }

    @Test
    void removesTheReverseInAnExternalChange() throws Exception
    {
        final NodeState before = this.rootWith(this.linkPath);
        this.removeAs(EDITOR, this.linkPath);

        this.remover.contentChanged(before, CommitInfo.EMPTY_EXTERNAL);
        this.remover.contentChanged(without(before, this.linkPath), CommitInfo.EMPTY_EXTERNAL);

        assertFalse(this.exists(this.reversePath));
    }

    @Test
    void removesTheReverseUnderCheckedInContent() throws Exception
    {
        final Node versioned = this.adminSession.getNode(THINGS).addNode("v");
        versioned.addMixin("mix:versionable");
        versioned.addMixin("link:Linkable");
        this.adminSession.save();
        final InternalLink link = this.link(A, versioned.getPath(), REFERENCES, null);
        final String reverse = link.getBacklink().getPath();
        final VersionManager versions = this.adminSession.getWorkspace().getVersionManager();
        versions.checkin(versioned.getPath());
        final NodeState before = this.rootWith(link.getPath());

        this.removeAs(EDITOR, link.getPath());
        assertFalse(versions.isCheckedOut(versioned.getPath()));
        this.observe(before, without(before, link.getPath()));

        assertFalse(this.exists(reverse));
    }

    @Test
    void theServiceRemovesTheReverseAfterTheCommit() throws Exception
    {
        final BacklinkRemovalObserver observer = this.context.registerInjectActivateService(
            new BacklinkRemovalObserver());
        final NodeState before = this.rootWith(this.linkPath);
        this.removeAs(EDITOR, this.linkPath);

        observer.contentChanged(before, CommitInfo.EMPTY);
        observer.contentChanged(without(before, this.linkPath), CommitInfo.EMPTY);

        final long deadline = System.currentTimeMillis() + 10_000;
        while (this.exists(this.reversePath) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        observer.deactivate();
        assertFalse(this.exists(this.reversePath));
    }

    /**
     * Defines the link types. {@code references} and {@code referencedBy} name each other, {@code cites} names
     * {@code citedBy} which names nothing back, {@code mentions} has no backlink, and {@code identifier} is external.
     */
    private void createDefinitions() throws RepositoryException
    {
        final Node types = this.adminSession.getRootNode().addNode("LinkTypes");
        this.definition(types, REFERENCES, InternalLinkDefinition.RESOURCE_TYPE)
            .setProperty(BACKLINK, "/LinkTypes/referencedBy");
        this.definition(types, "referencedBy", InternalLinkDefinition.RESOURCE_TYPE)
            .setProperty(BACKLINK, "/LinkTypes/" + REFERENCES);
        this.definition(types, "cites", InternalLinkDefinition.RESOURCE_TYPE)
            .setProperty(BACKLINK, "/LinkTypes/citedBy");
        this.definition(types, "citedBy", InternalLinkDefinition.RESOURCE_TYPE);
        this.definition(types, MENTIONS, InternalLinkDefinition.RESOURCE_TYPE);
        this.definition(types, "identifier", ExternalLinkDefinition.RESOURCE_TYPE);
        this.adminSession.save();
    }

    private Node definition(final Node types, final String name, final String resourceType)
        throws RepositoryException
    {
        final Node definition = types.addNode(name);
        definition.addMixin("mix:referenceable");
        definition.setProperty("sling:resourceType", resourceType);
        return definition;
    }

    private void createThings(final String... names) throws RepositoryException
    {
        final Node things = this.adminSession.getRootNode().addNode("Things");
        for (final String name : names) {
            final Node thing = things.addNode(name);
            thing.addMixin("mix:referenceable");
            thing.addNode(LinkManager.CONTAINER_NAME, "link:Links");
        }
        this.adminSession.save();
    }

    /** Links two things as the administrator, who adds the reverse in the same save. */
    private InternalLink link(final String source, final String destination, final String type, final String label)
        throws PersistenceException
    {
        final ResourceResolver admin = this.context.resourceResolver();
        final InternalLink link = this.manager.addLink(admin.getResource(source),
            admin.getResource(destination).adaptTo(Content.class), type, label);
        admin.commit();
        return link;
    }

    /**
     * Links {@code c} to {@code a} as a user who may change {@code c} only, so the reverse under {@code a} is left
     * for the backlinks listener.
     *
     * @return the path of the link
     */
    private String authorLinksCToA() throws RepositoryException, LoginException, PersistenceException
    {
        this.createUser(AUTHOR, C);
        try (ResourceResolver author = this.login(AUTHOR)) {
            final String path = this.manager.addLink(author.getResource(C),
                author.getResource(A).adaptTo(Content.class), REFERENCES, null).getPath();
            author.commit();
            return path;
        }
    }

    /** A factory serving the link type definitions, and without an {@code iap-links} service user. */
    private ResourceResolverFactory definitionsOnly()
    {
        return TestResolverFactory.withoutServiceUser()
            .withSubservice(LinkManagerImpl.DEFINITIONS_SUBSERVICE, this.context.resourceResolver());
    }

    /** Runs the backlinks listener on a link with a factory that has no {@code iap-links} service user. */
    private void addBacklinkWithoutAServiceUser(final String path)
    {
        this.context.registerService(ResourceResolverFactory.class, this.definitionsOnly(),
            Constants.SERVICE_RANKING, 1);
        this.context.registerInjectActivateService(new AutocreateBacklinksListener())
            .onChange(List.of(new ResourceChange(ResourceChange.ChangeType.ADDED, path, false)));
    }

    /** Creates a user who may read everything and change everything under one path. */
    private void createUser(final String name, final String writablePath) throws RepositoryException
    {
        final User user = ((JackrabbitSession) this.adminSession).getUserManager().createUser(name, name);
        AccessControlUtils.addAccessControlEntry(this.adminSession, "/", user.getPrincipal(),
            new String[] { Privilege.JCR_READ }, true);
        this.adminSession.save();
        this.allowToChange(name, writablePath);
    }

    private void allowToChange(final String name, final String path) throws RepositoryException
    {
        final User user = (User) ((JackrabbitSession) this.adminSession).getUserManager().getAuthorizable(name);
        AccessControlUtils.addAccessControlEntry(this.adminSession, path, user.getPrincipal(),
            new String[] { Privilege.JCR_ALL }, true);
        this.adminSession.save();
    }

    private ResourceResolver login(final String name) throws LoginException
    {
        return this.repository.getResourceResolver(Map.of(
            ResourceResolverFactory.USER, name,
            ResourceResolverFactory.PASSWORD, name.toCharArray()));
    }

    private void removeAs(final String name, final String path)
        throws LoginException, PersistenceException
    {
        try (ResourceResolver user = this.login(name)) {
            assertTrue(this.manager.remove(user.getResource(path)));
            user.commit();
        }
    }

    /** The editor removes the link from {@code a} to {@code b}, and the observer sees that commit. */
    private void editorRemovesTheLink() throws RepositoryException, LoginException, PersistenceException
    {
        final NodeState before = this.rootWith(this.linkPath);
        this.removeAs(EDITOR, this.linkPath);
        this.observe(before, without(before, this.linkPath));
    }

    private void observe(final NodeState before, final NodeState after)
    {
        this.remover.contentChanged(before, CommitInfo.EMPTY);
        this.remover.contentChanged(after, CommitInfo.EMPTY);
    }

    /**
     * A root holding copies of some link nodes from the repository, with the properties the observer reads.
     *
     * @param linkPaths the paths of the links to copy
     * @return the root
     */
    private NodeState rootWith(final String... linkPaths) throws RepositoryException
    {
        final NodeBuilder root = EmptyNodeState.EMPTY_NODE.builder();
        for (final String path : linkPaths) {
            final Node link = this.adminSession.getNode(path);
            final NodeBuilder copy = builderAt(root, path);
            copy.setProperty("jcr:primaryType", link.getPrimaryNodeType().getName(), Type.NAME);
            copy.setProperty(REFERENCE, link.getProperty(REFERENCE).getString(), Type.REFERENCE);
            copy.setProperty("type", link.getProperty("type").getString(), Type.REFERENCE);
            if (link.hasProperty(LABEL)) {
                copy.setProperty(LABEL, link.getProperty(LABEL).getString());
            }
        }
        return root.getNodeState();
    }

    private static NodeState without(final NodeState root, final String path)
    {
        final NodeBuilder builder = root.builder();
        builderAt(builder, path).remove();
        return builder.getNodeState();
    }

    private static NodeBuilder builderAt(final NodeBuilder root, final String path)
    {
        NodeBuilder builder = root;
        for (final String name : path.substring(1).split("/")) {
            builder = builder.child(name);
        }
        return builder;
    }

    private boolean exists(final String path) throws RepositoryException
    {
        this.adminSession.refresh(false);
        return this.adminSession.nodeExists(path);
    }
}
