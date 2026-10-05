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
import javax.jcr.version.VersionException;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VersioningUtils}: whichever versionable node holds content read-only is checked out, and
 * nothing is touched when the content is writable already.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class VersioningUtilsTest
{
    private static final String VERSIONABLE = "mix:versionable";

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

        assertEquals("/record", VersioningUtils.checkOut(detail));

        assertTrue(this.record.isCheckedOut());
        detail.setProperty("written", true);
        this.session.save();
    }

    @Test
    void checksOutAVersionableNodeItself() throws RepositoryException
    {
        assertEquals("/record", VersioningUtils.checkOut(this.record));

        assertTrue(this.record.isCheckedOut());
    }

    @Test
    void leavesWritableContentAlone() throws RepositoryException
    {
        this.session.getWorkspace().getVersionManager().checkout("/record");
        final Node unversioned = this.session.getRootNode().addNode("loose", "nt:unstructured");

        assertNull(VersioningUtils.checkOut(this.record.getNode("part")));
        assertNull(VersioningUtils.checkOut(unversioned));
    }

    @Test
    void stopsAtTheRootWhenNothingVersionableHoldsTheContent() throws RepositoryException
    {
        // Impossible in a conformant repository, where only a versionable node checks content in; the walk still
        // stops at the root rather than asking it for a parent
        final Node node = Mockito.mock(Node.class);
        final Node root = Mockito.mock(Node.class);
        Mockito.when(node.getDepth()).thenReturn(1);
        Mockito.when(node.getParent()).thenReturn(root);
        Mockito.when(node.getPath()).thenReturn("/orphan");

        final VersionException refusal = assertThrows(VersionException.class, () -> VersioningUtils.checkOut(node));

        assertTrue(refusal.getMessage().contains("/orphan"), refusal.getMessage());
        Mockito.verify(root, Mockito.never()).getParent();
    }
}
