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
package io.uhndata.iap.utils;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VersioningUtils}: whichever versionable node holds content read-only is checked out, and
 * nothing is touched when the content is writable already. A checked-out node is checked back in.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class VersioningUtilsTest
{
    private static final String VERSIONABLE = "mix:versionable";

    private static final String RECORD = "/record";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private Session session;

    private Node record;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        this.record = this.session.getRootNode().addNode("record", "nt:unstructured");
        this.record.addMixin(VERSIONABLE);
        this.record.addNode("part", "nt:unstructured").addNode("detail", "nt:unstructured");
        this.session.save();
        this.session.getWorkspace().getVersionManager().checkin(this.record.getPath());
    }

    @Test
    void checksOutTheVersionableNodeAboveWhatIsAboutToChange() throws RepositoryException
    {
        final Node detail = this.record.getNode("part/detail");
        assertFalse(detail.isCheckedOut(), "the record's check-in makes everything under it read-only");

        assertEquals(RECORD, VersioningUtils.checkOut(detail));

        assertTrue(this.record.isCheckedOut());
        detail.setProperty("written", true);
        this.session.save();
    }

    @Test
    void checksOutAVersionableNodeItself() throws RepositoryException
    {
        assertEquals(RECORD, VersioningUtils.checkOut(this.record));

        assertTrue(this.record.isCheckedOut());
    }

    @Test
    void leavesWritableContentAlone() throws RepositoryException
    {
        this.session.getWorkspace().getVersionManager().checkout(RECORD);
        final Node unversioned = this.session.getRootNode().addNode("loose", "nt:unstructured");

        assertNull(VersioningUtils.checkOut(this.record.getNode("part")));
        assertNull(VersioningUtils.checkOut(unversioned));
    }

    @Test
    void stopsAtTheRootWhenNothingVersionableHoldsTheContent() throws RepositoryException
    {
        // Only a mock can be checked in with nothing versionable above it
        final Node node = Mockito.mock(Node.class);
        final Node root = Mockito.mock(Node.class);
        Mockito.when(node.getDepth()).thenReturn(1);
        Mockito.when(node.getParent()).thenReturn(root);
        Mockito.when(node.getPath()).thenReturn("/orphan");

        final VersionException refusal = assertThrows(VersionException.class, () -> VersioningUtils.checkOut(node));

        assertTrue(refusal.getMessage().contains("/orphan"), refusal.getMessage());
        Mockito.verify(root, Mockito.never()).getParent();
    }

    @Test
    void checksOutWhatAResourceIs() throws PersistenceException, RepositoryException
    {
        final Resource part = this.context.resourceResolver().getResource("/record/part");

        assertEquals(RECORD, VersioningUtils.checkOut(part));

        assertTrue(this.record.isCheckedOut());
    }

    @Test
    void leavesAResourceThatIsNoNodeAlone() throws PersistenceException
    {
        assertNull(VersioningUtils.checkOut(Mockito.mock(Resource.class)));
    }

    @Test
    void reportsAResourceThatCannotBeCheckedOutAsAFailedWrite() throws RepositoryException
    {
        final Resource resource = Mockito.mock(Resource.class);
        final Node node = Mockito.mock(Node.class);
        final RepositoryException gone = new RepositoryException("gone");
        Mockito.when(node.isCheckedOut()).thenThrow(gone);
        Mockito.when(resource.adaptTo(Node.class)).thenReturn(node);
        Mockito.when(resource.getPath()).thenReturn(RECORD);

        final PersistenceException refusal =
            assertThrows(PersistenceException.class, () -> VersioningUtils.checkOut(resource));

        assertTrue(refusal.getMessage().contains(RECORD), refusal.getMessage());
        assertInstanceOf(RepositoryException.class, refusal.getCause());
    }

    @Test
    void checksInACheckedOutNode() throws PersistenceException, RepositoryException
    {
        this.session.getWorkspace().getVersionManager().checkout(RECORD);

        VersioningUtils.checkIn(this.context.resourceResolver(), RECORD);

        assertFalse(this.record.isCheckedOut());
    }

    @Test
    void leavesARemovedNodeAlone()
    {
        assertDoesNotThrow(() -> VersioningUtils.checkIn(this.context.resourceResolver(), "/gone"));
    }

    @Test
    void leavesACheckedInNodeAlone() throws PersistenceException, RepositoryException
    {
        // Checking in a checked-in node changes nothing in Oak, so only a mock shows it is not asked to
        final VersionManager versions = Mockito.mock(VersionManager.class);

        VersioningUtils.checkIn(resolverWith(versions), RECORD);

        Mockito.verify(versions, Mockito.never()).checkin(Mockito.anyString());
    }

    @Test
    void checksInNothingWithoutAJcrSession()
    {
        assertDoesNotThrow(() -> VersioningUtils.checkIn(Mockito.mock(ResourceResolver.class), RECORD));
    }

    @Test
    void reportsANodeThatCannotBeCheckedInAsAFailedWrite() throws RepositoryException
    {
        final VersionManager versions = Mockito.mock(VersionManager.class);
        Mockito.when(versions.isCheckedOut(RECORD)).thenThrow(new RepositoryException("gone"));
        final ResourceResolver resolver = resolverWith(versions);

        final PersistenceException refusal =
            assertThrows(PersistenceException.class, () -> VersioningUtils.checkIn(resolver, RECORD));

        assertTrue(refusal.getMessage().contains(RECORD), refusal.getMessage());
        assertInstanceOf(RepositoryException.class, refusal.getCause());
    }

    @Test
    void recordsAVersionHoldingWhatWasSavedAndLeavesTheNodeWritable() throws PersistenceException, RepositoryException
    {
        this.session.getWorkspace().getVersionManager().checkout(RECORD);
        this.record.setProperty("title", "As sent back");
        this.session.save();

        final String version = VersioningUtils.recordVersion(this.context.resourceResolver(), RECORD);

        assertTrue(this.record.isCheckedOut());
        assertEquals("As sent back",
            this.session.getNode(version).getNode("jcr:frozenNode").getProperty("title").getString());
    }

    @Test
    void recordsTheCurrentVersionOfACheckedInNodeAndChecksItOut() throws PersistenceException, RepositoryException
    {
        final String current = this.session.getWorkspace().getVersionManager().getBaseVersion(RECORD).getPath();

        assertEquals(current, VersioningUtils.recordVersion(this.context.resourceResolver(), RECORD));
        assertTrue(this.record.isCheckedOut());
    }

    @Test
    void recordsNothingForARemovedNodeOrWithoutAJcrSession() throws PersistenceException
    {
        assertNull(VersioningUtils.recordVersion(this.context.resourceResolver(), "/gone"));
        assertNull(VersioningUtils.recordVersion(Mockito.mock(ResourceResolver.class), RECORD));
    }

    @Test
    void reportsAVersionThatCannotBeRecordedAsAFailedWrite() throws RepositoryException
    {
        final VersionManager versions = Mockito.mock(VersionManager.class);
        Mockito.when(versions.checkin(RECORD)).thenThrow(new RepositoryException("locked"));
        final ResourceResolver resolver = resolverWith(versions);

        final PersistenceException refusal =
            assertThrows(PersistenceException.class, () -> VersioningUtils.recordVersion(resolver, RECORD));

        assertTrue(refusal.getMessage().contains(RECORD), refusal.getMessage());
    }

    /**
     * Mocks a resolver whose JCR session has a node at {@code /record}, versioned through a given version manager.
     *
     * @param versions the version manager the session's workspace answers with
     * @return the mocked resolver
     */
    private static ResourceResolver resolverWith(final VersionManager versions) throws RepositoryException
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        final Session session = Mockito.mock(Session.class);
        final Workspace workspace = Mockito.mock(Workspace.class);
        Mockito.when(resolver.adaptTo(Session.class)).thenReturn(session);
        Mockito.when(session.getWorkspace()).thenReturn(workspace);
        Mockito.when(session.nodeExists(RECORD)).thenReturn(true);
        Mockito.when(workspace.getVersionManager()).thenReturn(versions);
        return resolver;
    }
}
