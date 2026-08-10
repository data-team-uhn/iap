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
package io.uhndata.iap.deletion.internal;

import java.lang.reflect.Field;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionImpact;
import io.uhndata.iap.deletion.api.DeletionOptions;
import io.uhndata.iap.deletion.api.DeletionResult;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.RestoreConflict;
import io.uhndata.iap.deletion.api.RestoreResult;
import io.uhndata.iap.deletion.spi.DeletionVeto;
import io.uhndata.iap.links.internal.LinkManagerImpl;
import io.uhndata.iap.links.internal.LinkOperations;
import io.uhndata.iap.links.models.ExternalLink;
import io.uhndata.iap.links.models.InternalLink;
import io.uhndata.iap.links.models.LinkDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DeletionServiceImpl}, against a real in-memory repository so that back-references, moves,
 * referential integrity, versioning and permissions behave like production.
 *
 * @version $Id$
 */
@ExtendWith(SlingContextExtension.class)
class DeletionServiceImplTest
{
    private static final String CONTENT = "/content";

    private static final String VICTIM = "victim";

    private static final String VICTIM_PATH = CONTENT + "/victim";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private DeletionServiceImpl service;

    private Session session;

    private VersionManager versionManager;

    @BeforeEach
    void setup() throws Exception
    {
        this.context.addModelsForClasses(Content.class, InternalLink.class, ExternalLink.class,
            LinkDefinition.class);
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        this.versionManager = this.session.getWorkspace().getVersionManager();
        NodeTypeDefinitionScanner.get().register(this.session,
            List.of("SLING-INF/nodetypes/deletion.cnd", "SLING-INF/nodetypes/test.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.session.getRootNode().addNode("Archive", DeletionService.ARCHIVE_NODETYPE);
        this.session.getRootNode().addNode("content");
        this.session.getRootNode().addNode("LinkTypes");
        this.session.save();

        final LinkManagerImpl linkManager = new LinkManagerImpl();
        inject(linkManager, "resolverFactory", new TestResolverFactory(this.context.resourceResolver()));
        // Link removal is behavior on the Link models, delegating to this service
        this.context.registerService(LinkOperations.class, linkManager);
        this.service = new DeletionServiceImpl();
        inject(this.service, "resolverFactory", new TestResolverFactory(this.context.resourceResolver()));
        inject(this.service, "linkManager", linkManager);
        inject(this.service, "vetoes", List.of(new UndeletableVeto()));
    }

    private static void inject(final Object target, final String fieldName, final Object value) throws Exception
    {
        final Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Node target(final String name) throws RepositoryException
    {
        final Node node = this.session.getNode(CONTENT).addNode(name);
        node.addMixin("mix:referenceable");
        this.session.save();
        return node;
    }

    private Node referrer(final String name, final Node... targets) throws RepositoryException
    {
        final Node node = this.session.getNode(CONTENT).addNode(name);
        node.addMixin("mix:referenceable");
        int index = 0;
        for (final Node reference : targets) {
            node.setProperty("ref" + index, reference);
            index++;
        }
        this.session.save();
        return node;
    }

    private Node definition(final String name, final String onDelete, final boolean weak)
        throws RepositoryException
    {
        final Node node = this.session.getNode("/LinkTypes").addNode(name, "iap:LinkDefinition");
        node.setProperty("onDelete", onDelete);
        node.setProperty("weak", weak);
        this.session.save();
        return node;
    }

    private Node link(final Node owner, final Node destination, final Node definition, final boolean weak)
        throws RepositoryException
    {
        final Node container = owner.hasNode("iap:links") ? owner.getNode("iap:links")
            : owner.addNode("iap:links", "iap:Links");
        final Node link = container.addNode("link" + container.getNodes().getSize(),
            weak ? "iap:WeakLink" : "iap:Link");
        link.setProperty("type", definition);
        if (weak) {
            link.setProperty("reference", this.session.getValueFactory().createValue(destination, true));
        } else {
            link.setProperty("reference", destination);
        }
        this.session.save();
        return link;
    }

    private Resource resource(final String path)
    {
        this.context.resourceResolver().refresh();
        return this.context.resourceResolver().getResource(path);
    }

    private DeletionResult delete(final String path, final boolean recursive, final boolean permanent)
    {
        return this.service.delete(this.resource(path), DeletionOptions.of(recursive, permanent));
    }

    /** A resource whose resolver adapts to a doctored session, standing in for a less privileged user. */
    private Resource withUserSession(final String path, final Session userSession)
    {
        final ResourceResolver wrapped = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == Session.class ? (T) userSession : super.adaptTo(type);
            }
        };
        return new ResourceWrapper(this.resource(path))
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return wrapped;
            }
        };
    }

    @Test
    void simpleDeletionArchives() throws Exception
    {
        final Node node = this.target(VICTIM);
        final String id = node.getIdentifier();
        final DeletionResult result = this.delete(VICTIM_PATH, false, false);
        assertEquals(DeletionResult.Status.ARCHIVED, result.getStatus());
        assertEquals(List.of(VICTIM_PATH), result.getImpact().getItemPaths());
        assertFalse(this.session.nodeExists(VICTIM_PATH));
        final Node entry = this.session.getNode(result.getArchiveEntryPath());
        assertEquals("admin", entry.getProperty(DeletionService.DELETED_BY_PROPERTY).getString());
        assertEquals(VICTIM_PATH, entry.getProperty(DeletionService.REQUESTED_PATH_PROPERTY).getString());
        final Node wrapper = entry.getNode("0");
        assertEquals(VICTIM_PATH,
            wrapper.getProperty(DeletionService.ORIGINAL_PATH_PROPERTY).getString());
        assertEquals(id, wrapper.getNode(VICTIM).getIdentifier());
    }

    @Test
    void archiveEntriesAreFiledUnderAPrefixTree() throws Exception
    {
        this.target(VICTIM);
        final String entryPath = this.delete(VICTIM_PATH, false, false).getArchiveEntryPath();
        final Node entry = this.session.getNode(entryPath);
        assertEquals(DeletionService.ARCHIVE_PATH + "/" + entry.getName().substring(0, 2) + "/"
            + entry.getName().substring(2, 4) + "/" + entry.getName().substring(4, 6) + "/" + entry.getName(),
            entryPath);
        // The buckets are archive nodes themselves, and hold nothing but the entry
        Node bucket = entry.getParent();
        while (!DeletionService.ARCHIVE_PATH.equals(bucket.getPath())) {
            assertTrue(bucket.isNodeType(DeletionService.ARCHIVE_NODETYPE));
            assertEquals(1, bucket.getNodes().getSize());
            bucket = bucket.getParent();
        }
    }

    @Test
    void permanentDeletionLeavesNoTrace() throws Exception
    {
        this.target(VICTIM);
        final long entriesBefore = this.session.getNode(DeletionService.ARCHIVE_PATH).getNodes().getSize();
        final DeletionResult result = this.delete(VICTIM_PATH, false, true);
        assertEquals(DeletionResult.Status.DELETED, result.getStatus());
        assertFalse(this.session.nodeExists(VICTIM_PATH));
        assertEquals(entriesBefore, this.session.getNode(DeletionService.ARCHIVE_PATH).getNodes().getSize());
    }

    @Test
    void analyzeChangesNothing() throws Exception
    {
        final Node node = this.target(VICTIM);
        this.referrer("holder", node, node);
        final DeletionImpact impact =
            this.service.analyze(this.resource(VICTIM_PATH), DeletionOptions.archive());
        assertFalse(impact.isExecutable());
        assertEquals(1, impact.getReferrers().size());
        assertEquals(1, impact.getReferrers().get(0).getCount());
        assertTrue(this.session.nodeExists(VICTIM_PATH));
        assertTrue(this.session.nodeExists("/content/holder"));
    }

    @Test
    void referencedItemRequiresConfirmation() throws Exception
    {
        final Node node = this.target(VICTIM);
        this.referrer("holder", node);
        final DeletionResult result = this.delete(VICTIM_PATH, false, false);
        assertEquals(DeletionResult.Status.REQUIRES_CONFIRMATION, result.getStatus());
        assertTrue(result.getImpact().getSummary().contains("referenced by 1"));
        assertTrue(this.session.nodeExists(VICTIM_PATH));
    }

    @Test
    void recursiveDeletionCascadesOverReferrers() throws Exception
    {
        final Node node = this.target(VICTIM);
        final Node holder = this.referrer("holder", node, node);
        final String holderId = holder.getIdentifier();
        final DeletionResult result = this.delete(VICTIM_PATH, true, false);
        assertEquals(DeletionResult.Status.ARCHIVED, result.getStatus());
        assertEquals(List.of("/content/holder", VICTIM_PATH), result.getImpact().getItemPaths());
        final Node entry = this.session.getNode(result.getArchiveEntryPath());
        assertEquals(2, entry.getNodes().getSize());
        // The reference is carried into the archive intact
        final Node archivedHolder = this.session.getNodeByIdentifier(holderId);
        assertTrue(archivedHolder.getPath().startsWith(DeletionService.ARCHIVE_PATH));
        assertTrue(archivedHolder.getProperty("ref0").getNode().getPath()
            .startsWith(DeletionService.ARCHIVE_PATH));
    }

    @Test
    void nestedReferrersCollapseIntoTheirAncestor() throws Exception
    {
        final Node node = this.target(VICTIM);
        final Node sub = node.addNode("sub");
        sub.addMixin("mix:referenceable");
        final Node parent = this.session.getNode(CONTENT).addNode("parent");
        // The child referrer is found first, while scanning the victim itself; the parent referrer is found
        // later, while scanning the victim's child, and swallows the already processed child referrer
        parent.addNode("child").setProperty("ref", node);
        parent.setProperty("ref", sub);
        this.session.save();
        final DeletionResult result = this.delete(VICTIM_PATH, true, false);
        assertEquals(DeletionResult.Status.ARCHIVED, result.getStatus());
        assertEquals(List.of("/content/parent", VICTIM_PATH), result.getImpact().getItemPaths());
    }

    @Test
    void removeLinkPolicyOnlyRemovesTheLink() throws Exception
    {
        final Node node = this.target(VICTIM);
        final Node holder = this.referrer("holder");
        final Node link = this.link(holder, node, this.definition("related", "REMOVE_LINK", false), false);
        final String linkPath = link.getPath();
        final DeletionResult result = this.delete(VICTIM_PATH, false, false);
        assertEquals(DeletionResult.Status.ARCHIVED, result.getStatus());
        assertEquals(List.of(linkPath), result.getImpact().getRemovedLinkPaths());
        assertTrue(this.session.nodeExists("/content/holder"));
        assertFalse(this.session.nodeExists(linkPath));
    }

    @Test
    void ignorePolicyLeavesWeakLinksDangling() throws Exception
    {
        final Node node = this.target(VICTIM);
        final Node holder = this.referrer("holder");
        final Node link = this.link(holder, node, this.definition("seen", "IGNORE", true), true);
        final DeletionResult result = this.delete(VICTIM_PATH, false, false);
        assertEquals(DeletionResult.Status.ARCHIVED, result.getStatus());
        assertTrue(result.getImpact().getRemovedLinkPaths().isEmpty());
        assertTrue(this.session.nodeExists(link.getPath()));
    }

    @Test
    void ignorePolicyOnHardLinksIsDowngradedToRemoval() throws Exception
    {
        final Node node = this.target(VICTIM);
        final Node holder = this.referrer("holder");
        final Node link = this.link(holder, node, this.definition("illegal", "IGNORE", false), false);
        final String linkPath = link.getPath();
        final DeletionResult result = this.delete(VICTIM_PATH, false, true);
        assertEquals(DeletionResult.Status.DELETED, result.getStatus());
        assertFalse(this.session.nodeExists(linkPath));
        assertTrue(this.session.nodeExists("/content/holder"));
    }

    @Test
    void recursiveDeletePolicyRequiresConfirmation() throws Exception
    {
        final Node node = this.target(VICTIM);
        final Node holder = this.referrer("holder");
        this.link(holder, node, this.definition("vital", "RECURSIVE_DELETE", false), false);
        final DeletionResult refused = this.delete(VICTIM_PATH, false, false);
        assertEquals(DeletionResult.Status.REQUIRES_CONFIRMATION, refused.getStatus());
        assertEquals("holder", refused.getImpact().getReferrers().get(0).getNames().get(0));
    }

    @Test
    void recursiveDeletePolicyDragsTheLinkingResource() throws Exception
    {
        final Node node = this.target(VICTIM);
        final Node holder = this.referrer("holder");
        this.link(holder, node, this.definition("vital", "RECURSIVE_DELETE", false), false);
        final DeletionResult result = this.delete(VICTIM_PATH, true, false);
        assertEquals(DeletionResult.Status.ARCHIVED, result.getStatus());
        assertEquals(List.of("/content/holder", VICTIM_PATH), result.getImpact().getItemPaths());
        assertFalse(this.session.nodeExists("/content/holder"));
    }

    @Test
    void mutualRecursiveLinksTerminate() throws Exception
    {
        final Node first = this.target("first");
        final Node second = this.target("second");
        final Node vital = this.definition("vital", "RECURSIVE_DELETE", false);
        this.link(first, second, vital, false);
        this.link(second, first, vital, false);
        final DeletionResult result = this.delete("/content/first", true, false);
        assertEquals(DeletionResult.Status.ARCHIVED, result.getStatus());
        assertEquals(List.of("/content/first", "/content/second"), result.getImpact().getItemPaths());
    }

    @Test
    void malformedRecursiveLinkOnlyRemovesTheLink() throws Exception
    {
        final Node node = this.target(VICTIM);
        final Node vital = this.definition("vital", "RECURSIVE_DELETE", false);
        // A link node sitting directly under the root, with no owning resource two levels up
        final Node link = this.session.getRootNode().addNode("stray", "iap:Link");
        link.setProperty("type", vital);
        link.setProperty("reference", node);
        this.session.save();
        final DeletionResult result = this.delete(VICTIM_PATH, false, false);
        assertEquals(DeletionResult.Status.ARCHIVED, result.getStatus());
        assertFalse(this.session.nodeExists("/stray"));
    }

    @Test
    void deletingADefinitionRemovesItsLinks() throws Exception
    {
        final Node node = this.target(VICTIM);
        final Node holder = this.referrer("holder");
        final Node definition = this.definition("expendable", "RECURSIVE_DELETE", false);
        final Node link = this.link(holder, node, definition, false);
        final Node external = this.session.getNode(CONTENT).addNode("external");
        final Node externalDefinition = this.definition("ehr", "REMOVE_LINK", false);
        externalDefinition.setProperty("external", true);
        final Node externalContainer = external.addNode("iap:links", "iap:Links");
        final Node externalLink = externalContainer.addNode("ext", "iap:ExternalLink");
        externalLink.setProperty("type", externalDefinition);
        externalLink.setProperty("value", "12345");
        this.session.save();
        // Even a RECURSIVE_DELETE definition does not cascade over the residing links when the definition itself
        // is what is being deleted: the links are simply removed
        final String linkPath = link.getPath();
        final String externalLinkPath = externalLink.getPath();
        final DeletionResult first = this.delete(definition.getPath(), false, false);
        assertEquals(DeletionResult.Status.ARCHIVED, first.getStatus());
        assertFalse(this.session.nodeExists(linkPath));
        assertTrue(this.session.nodeExists("/content/holder"));
        final DeletionResult second = this.delete(externalDefinition.getPath(), false, false);
        assertEquals(DeletionResult.Status.ARCHIVED, second.getStatus());
        assertFalse(this.session.nodeExists(externalLinkPath));
        assertTrue(this.session.nodeExists("/content/external"));
    }

    @Test
    void archivedReferrersBlockOnlyPermanentDeletion() throws Exception
    {
        final Node node = this.target(VICTIM);
        this.referrer("holder", node);
        assertEquals(DeletionResult.Status.ARCHIVED, this.delete("/content/holder", false, false).getStatus());
        // The holder is archived and still references the victim; archiving the victim is fine...
        final DeletionImpact archival =
            this.service.analyze(this.resource(VICTIM_PATH), DeletionOptions.archive());
        assertTrue(archival.isExecutable());
        // ...but permanently deleting it would break somebody's archived data, so it is refused
        final DeletionResult result = this.delete(VICTIM_PATH, false, true);
        assertEquals(DeletionResult.Status.REQUIRES_CONFIRMATION, result.getStatus());
        assertEquals(1, result.getImpact().getInaccessibleReferrerCount());
        assertTrue(result.getImpact().getSummary().contains("you cannot see"));
    }

    @Test
    void archivedLinksAreRemovedOnPermanentDeletion() throws Exception
    {
        final Node node = this.target(VICTIM);
        final Node holder = this.referrer("holder");
        this.link(holder, node, this.definition("related", "REMOVE_LINK", false), false);
        final Node weakHolder = this.referrer("weakHolder");
        this.link(weakHolder, node, this.definition("seen", "IGNORE", true), true);
        assertEquals(DeletionResult.Status.ARCHIVED, this.delete("/content/holder", false, false).getStatus());
        assertEquals(DeletionResult.Status.ARCHIVED,
            this.delete("/content/weakHolder", false, false).getStatus());
        // Archiving the target leaves the archived links alone
        final DeletionImpact archival =
            this.service.analyze(this.resource(VICTIM_PATH), DeletionOptions.archive());
        assertTrue(archival.isExecutable());
        assertTrue(archival.getRemovedLinkPaths().isEmpty());
        // Permanently deleting it removes the archived hard link, while the weak one just dangles
        final DeletionResult result = this.delete(VICTIM_PATH, false, true);
        assertEquals(DeletionResult.Status.DELETED, result.getStatus());
        assertEquals(1, result.getImpact().getRemovedLinkPaths().size());
    }

    @Test
    void undeletableResourcesAreVetoed() throws Exception
    {
        final Node node = this.target(VICTIM);
        node.addNode("part").addMixin(DeletionService.UNDELETABLE_MIXIN);
        this.session.save();
        final DeletionResult result = this.delete(VICTIM_PATH, false, false);
        assertEquals(DeletionResult.Status.VETOED, result.getStatus());
        assertEquals("/content/victim/part", result.getImpact().getVetoes().get(0).getPath());
        assertEquals("undeletable", result.getImpact().getVetoes().get(0).getVetoerName());
        assertTrue(this.session.nodeExists(VICTIM_PATH));
    }

    @Test
    void failingGuardsFailClosed() throws Exception
    {
        this.target(VICTIM);
        final DeletionVeto broken = mock(DeletionVeto.class);
        when(broken.getName()).thenReturn("broken");
        when(broken.veto(any(), any())).thenThrow(new RepositoryException("cannot decide"));
        inject(this.service, "vetoes", List.of(broken));
        final DeletionResult result = this.delete(VICTIM_PATH, false, false);
        assertEquals(DeletionResult.Status.VETOED, result.getStatus());
        assertTrue(result.getImpact().getVetoes().get(0).getReason().contains("Could not verify"));
    }

    @Test
    void missingGuardsMeanNoVetoes() throws Exception
    {
        this.target(VICTIM);
        inject(this.service, "vetoes", null);
        assertEquals(DeletionResult.Status.ARCHIVED, this.delete(VICTIM_PATH, false, false).getStatus());
    }

    @Test
    void deletionWithoutRemoveRightsIsDenied() throws Exception
    {
        this.target(VICTIM);
        final Session restricted = mock(Session.class, delegatesTo(this.session));
        doReturn(false).when(restricted).hasPermission(anyString(), eq(Session.ACTION_REMOVE));
        final DeletionResult result = this.service.delete(
            this.withUserSession(VICTIM_PATH, restricted), DeletionOptions.archive());
        assertEquals(DeletionResult.Status.DENIED, result.getStatus());
        assertTrue(this.session.nodeExists(VICTIM_PATH));
    }

    @Test
    void missingServiceUserFailsCleanly() throws Exception
    {
        this.target(VICTIM);
        inject(this.service, "resolverFactory", new TestResolverFactory(null));
        assertThrows(DeletionException.class,
            () -> this.service.analyze(this.resource(VICTIM_PATH), DeletionOptions.archive()));
    }

    @Test
    void protectedPathsAreRejected() throws Exception
    {
        this.target(VICTIM);
        final DeletionResult archival = this.delete(VICTIM_PATH, false, false);
        assertEquals(DeletionResult.Status.ARCHIVED, archival.getStatus());
        assertThrows(IllegalArgumentException.class,
            () -> this.service.delete(this.resource(DeletionService.ARCHIVE_PATH), DeletionOptions.archive()));
        final Resource archived = this.resource(archival.getArchiveEntryPath());
        assertThrows(IllegalArgumentException.class,
            () -> this.service.delete(archived, DeletionOptions.archive()));
        // Including the prefix tree the entry is filed under
        final Resource bucket = this.resource(archived.getParent().getPath());
        assertThrows(IllegalArgumentException.class,
            () -> this.service.delete(bucket, DeletionOptions.archive()));
        assertThrows(IllegalArgumentException.class,
            () -> this.service.delete(this.resource("/"), DeletionOptions.archive()));
    }

    @Test
    void nonRepositoryResourcesAreRejected()
    {
        final Resource synthetic = mock(Resource.class);
        when(synthetic.getPath()).thenReturn("/content/ghost");
        assertThrows(IllegalArgumentException.class,
            () -> this.service.analyze(synthetic, DeletionOptions.archive()));
    }

    @Test
    void nonRepositoryUserSessionsAreRejected() throws Exception
    {
        this.target(VICTIM);
        assertThrows(DeletionException.class,
            () -> this.service.analyze(this.withUserSession(VICTIM_PATH, null),
                DeletionOptions.archive()));
    }

    @Test
    void repositoryFailuresAreWrapped() throws Exception
    {
        this.target(VICTIM);
        final Session failing = mock(Session.class, delegatesTo(this.session));
        doThrow(new RepositoryException("boom")).when(failing).hasPermission(anyString(), anyString());
        final Resource item = this.withUserSession(VICTIM_PATH, failing);
        assertThrows(DeletionException.class, () -> this.service.analyze(item, DeletionOptions.archive()));
        assertThrows(DeletionException.class, () -> this.service.delete(item, DeletionOptions.archive()));
    }

    @Test
    void checkedInAncestorsAreCheckedOutAndBackIn() throws Exception
    {
        final Node parent = this.session.getNode(CONTENT).addNode("parent");
        parent.addMixin("mix:versionable");
        parent.addNode("child");
        this.session.save();
        this.versionManager.checkin("/content/parent");
        assertEquals(DeletionResult.Status.ARCHIVED, this.delete("/content/parent/child", false, false)
            .getStatus());
        assertFalse(this.session.nodeExists("/content/parent/child"));
        assertFalse(this.versionManager.isCheckedOut("/content/parent"));
    }

    @Test
    void checkedOutAncestorsAreLeftCheckedOut() throws Exception
    {
        final Node parent = this.session.getNode(CONTENT).addNode("parent");
        parent.addMixin("mix:versionable");
        parent.addNode("child");
        this.session.save();
        assertEquals(DeletionResult.Status.ARCHIVED, this.delete("/content/parent/child", false, false)
            .getStatus());
        assertTrue(this.versionManager.isCheckedOut("/content/parent"));
    }

    @Test
    void linksOfCheckedInResourcesAreRemovedWithoutCheckout() throws Exception
    {
        final Node node = this.target(VICTIM);
        final Node holder = this.session.getNode(CONTENT).addNode("holder", "test:Entity");
        this.session.save();
        this.link(holder, node, this.definition("related", "REMOVE_LINK", false), false);
        this.versionManager.checkin("/content/holder");
        final DeletionResult result = this.delete(VICTIM_PATH, false, false);
        assertEquals(DeletionResult.Status.ARCHIVED, result.getStatus());
        assertFalse(this.session.getNode("/content/holder/iap:links").getNodes().hasNext());
        assertFalse(this.versionManager.isCheckedOut("/content/holder"));
    }

    @Test
    void restorePutsEverythingBack() throws Exception
    {
        final Node node = this.target(VICTIM);
        final String id = node.getIdentifier();
        this.referrer("holder", node);
        final DeletionResult deleted = this.delete(VICTIM_PATH, true, false);
        final RestoreResult result = this.service.restore(this.resource(deleted.getArchiveEntryPath()));
        assertEquals(RestoreResult.Status.RESTORED, result.getStatus());
        assertEquals(List.of("/content/holder", VICTIM_PATH), result.getRestoredPaths());
        assertEquals(id, this.session.getNode(VICTIM_PATH).getIdentifier());
        assertEquals(VICTIM_PATH, this.session.getNode("/content/holder")
            .getProperty("ref0").getNode().getPath());
        assertFalse(this.session.nodeExists(deleted.getArchiveEntryPath()));
    }

    @Test
    void restoreIntoCheckedInParentChecksItOutAndBackIn() throws Exception
    {
        final Node parent = this.session.getNode(CONTENT).addNode("parent");
        parent.addMixin("mix:versionable");
        parent.addNode("child");
        this.session.save();
        final DeletionResult deleted = this.delete("/content/parent/child", false, false);
        this.versionManager.checkin("/content/parent");
        final RestoreResult result = this.service.restore(this.resource(deleted.getArchiveEntryPath()));
        assertEquals(RestoreResult.Status.RESTORED, result.getStatus());
        assertTrue(this.session.nodeExists("/content/parent/child"));
        assertFalse(this.versionManager.isCheckedOut("/content/parent"));
    }

    @Test
    void restoreRefusesOccupiedPaths() throws Exception
    {
        this.target(VICTIM);
        final DeletionResult deleted = this.delete(VICTIM_PATH, false, false);
        this.target(VICTIM);
        final RestoreResult result = this.service.restore(this.resource(deleted.getArchiveEntryPath()));
        assertEquals(RestoreResult.Status.CONFLICT, result.getStatus());
        assertEquals(RestoreConflict.Reason.OCCUPIED, result.getConflicts().get(0).getReason());
        assertTrue(this.session.nodeExists(deleted.getArchiveEntryPath()));
    }

    @Test
    void restoreRefusesWhenTheParentIsGone() throws Exception
    {
        this.session.getNode(CONTENT).addNode("area").addNode(VICTIM).addMixin("mix:referenceable");
        this.session.save();
        final DeletionResult deleted = this.delete("/content/area/victim", false, false);
        assertEquals(DeletionResult.Status.DELETED, this.delete("/content/area", false, true).getStatus());
        final RestoreResult result = this.service.restore(this.resource(deleted.getArchiveEntryPath()));
        assertEquals(RestoreResult.Status.CONFLICT, result.getStatus());
        assertEquals(RestoreConflict.Reason.PARENT_MISSING, result.getConflicts().get(0).getReason());
    }

    @Test
    void restoreRefusesWithoutAddRights() throws Exception
    {
        this.target(VICTIM);
        final DeletionResult deleted = this.delete(VICTIM_PATH, false, false);
        final Session restricted = mock(Session.class, delegatesTo(this.session));
        doReturn(false).when(restricted).hasPermission(anyString(), eq(Session.ACTION_ADD_NODE));
        final RestoreResult result =
            this.service.restore(this.withUserSession(deleted.getArchiveEntryPath(), restricted));
        assertEquals(RestoreResult.Status.CONFLICT, result.getStatus());
        assertEquals(RestoreConflict.Reason.NO_RIGHTS, result.getConflicts().get(0).getReason());
    }

    @Test
    void restoreSkipsForeignAndEmptyItems() throws Exception
    {
        this.target(VICTIM);
        final DeletionResult deleted = this.delete(VICTIM_PATH, false, false);
        final Node entry = this.session.getNode(deleted.getArchiveEntryPath());
        entry.addNode("empty", DeletionService.ITEM_NODETYPE).setProperty(
            DeletionService.ORIGINAL_PATH_PROPERTY, "/content/nothing");
        entry.addNode("foreign", "nt:folder");
        this.session.save();
        final RestoreResult result = this.service.restore(this.resource(deleted.getArchiveEntryPath()));
        assertEquals(RestoreResult.Status.RESTORED, result.getStatus());
        assertEquals(List.of(VICTIM_PATH), result.getRestoredPaths());
    }

    @Test
    void restoreRejectsNonEntries() throws Exception
    {
        this.target(VICTIM);
        assertThrows(IllegalArgumentException.class,
            () -> this.service.restore(this.resource(VICTIM_PATH)));
        final Resource synthetic = mock(Resource.class);
        when(synthetic.getPath()).thenReturn("/Archive/ghost");
        assertThrows(IllegalArgumentException.class, () -> this.service.restore(synthetic));
    }

    @Test
    void purgeRemovesTheEntryForGood() throws Exception
    {
        this.target(VICTIM);
        final DeletionResult deleted = this.delete(VICTIM_PATH, false, false);
        final DeletionResult result = this.service.purge(this.resource(deleted.getArchiveEntryPath()));
        assertEquals(DeletionResult.Status.DELETED, result.getStatus());
        assertFalse(this.session.nodeExists(deleted.getArchiveEntryPath()));
    }

    @Test
    void purgeIsVetoedByProtectedContents() throws Exception
    {
        this.target(VICTIM);
        final DeletionResult deleted = this.delete(VICTIM_PATH, false, false);
        this.session.getNode(deleted.getArchiveEntryPath()).getNode("0/victim")
            .addMixin(DeletionService.UNDELETABLE_MIXIN);
        this.session.save();
        final DeletionResult result = this.service.purge(this.resource(deleted.getArchiveEntryPath()));
        assertEquals(DeletionResult.Status.VETOED, result.getStatus());
        assertTrue(this.session.nodeExists(deleted.getArchiveEntryPath()));
    }

    @Test
    void purgeRejectsNonEntries() throws Exception
    {
        this.target(VICTIM);
        assertThrows(IllegalArgumentException.class,
            () -> this.service.purge(this.resource(VICTIM_PATH)));
    }

    @Test
    void entryLookupFailuresAreWrapped() throws Exception
    {
        final Node failing = mock(Node.class);
        when(failing.isNodeType(anyString())).thenThrow(new RepositoryException("boom"));
        final Resource fake = new ResourceWrapper(this.resource("/Archive"))
        {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == Node.class ? (T) failing : super.adaptTo(type);
            }
        };
        assertThrows(DeletionException.class, () -> this.service.restore(fake));
        assertThrows(DeletionException.class, () -> this.service.purge(fake));
    }

    @Test
    void deletionsRecordTheRealRequester() throws Exception
    {
        this.target(VICTIM);
        final Session named = mock(Session.class, delegatesTo(this.session));
        doReturn("jdoe").when(named).getUserID();
        final DeletionResult result = this.service.delete(
            this.withUserSession(VICTIM_PATH, named), DeletionOptions.archive());
        assertEquals(DeletionResult.Status.ARCHIVED, result.getStatus());
        assertEquals("jdoe", this.session.getNode(result.getArchiveEntryPath())
            .getProperty(DeletionService.DELETED_BY_PROPERTY).getString());
    }

    @Test
    void topLevelResourcesCanBeRestored() throws Exception
    {
        final Node node = this.session.getRootNode().addNode("floater");
        node.addMixin("mix:referenceable");
        this.session.save();
        final DeletionResult deleted = this.delete("/floater", false, false);
        assertEquals(DeletionResult.Status.ARCHIVED, deleted.getStatus());
        final RestoreResult result = this.service.restore(this.resource(deleted.getArchiveEntryPath()));
        assertEquals(RestoreResult.Status.RESTORED, result.getStatus());
        assertTrue(this.session.nodeExists("/floater"));
        this.delete("/floater", false, true);
    }

    @Test
    void sessionlessServiceResolverIsRefused() throws Exception
    {
        this.target(VICTIM);
        final ResourceResolver sessionless = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return null;
            }
        };
        inject(this.service, "resolverFactory", new TestResolverFactory(sessionless));
        assertThrows(DeletionException.class,
            () -> this.service.analyze(this.resource(VICTIM_PATH), DeletionOptions.archive()));
    }
}
