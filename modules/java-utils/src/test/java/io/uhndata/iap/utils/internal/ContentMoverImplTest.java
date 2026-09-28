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
package io.uhndata.iap.utils.internal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.utils.move.MoveParticipant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ContentMoverImpl}: a node moves with what it holds, keeps its name unless taken, goes where
 * it is asked among its new siblings, and participants prepare only when its path changes.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ContentMoverImplTest
{
    private static final String UNSTRUCTURED = "nt:unstructured";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final ContentMoverImpl mover = new ContentMoverImpl();

    private final List<String> prepared = new ArrayList<>();

    private Session session;

    private Node from;

    private Node to;

    @BeforeEach
    void setUp() throws RepositoryException, ReflectiveOperationException
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        final Node root = this.session.getRootNode();
        this.from = root.addNode("from", UNSTRUCTURED);
        this.from.addNode("a", UNSTRUCTURED).addNode("inside", UNSTRUCTURED);
        this.from.addNode("b", UNSTRUCTURED);
        this.from.addNode("c", UNSTRUCTURED);
        this.to = root.addNode("to", UNSTRUCTURED);
        this.to.addNode("x", UNSTRUCTURED);
        this.to.addNode("a", UNSTRUCTURED);
        this.session.save();

        final Field field = ContentMoverImpl.class.getDeclaredField("participants");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        final List<MoveParticipant> participants = (List<MoveParticipant>) field.get(this.mover);
        participants.add(new MoveParticipant()
        {
            @Override
            public void beforeMove(final Node node, final String newPath) throws RepositoryException
            {
                ContentMoverImplTest.this.prepared.add(node.getPath() + " > " + newPath);
            }
        });
    }

    @Test
    void movesANodeWithWhatItHoldsIntoAnotherParent() throws RepositoryException
    {
        assertEquals("/to/b", this.mover.move(this.from.getNode("b"), this.to, "b", "x"));

        assertEquals(List.of("b", "x", "a"), children(this.to));
        assertEquals(List.of("a", "c"), children(this.from));
        assertEquals(List.of("/from/b > /to/b"), this.prepared);
    }

    @Test
    void takesTheNameItIsGiven() throws RepositoryException
    {
        assertEquals("/to/a2", this.mover.move(this.from.getNode("a"), this.to, "a2", null));

        assertEquals(List.of("x", "a", "a2"), children(this.to));
        assertTrue(this.session.nodeExists("/to/a2/inside"));
    }

    @Test
    void changesOnlyItsPlaceAmongItsSiblings() throws RepositoryException
    {
        this.mover.move(this.from.getNode("c"), this.from, "c", "a");
        assertEquals(List.of("c", "a", "b"), children(this.from));
        this.mover.move(this.from.getNode("c"), this.from, "c", null);
        assertEquals(List.of("a", "b", "c"), children(this.from));
        this.mover.move(this.from.getNode("b"), this.from, "b", "b");
        assertEquals(List.of("a", "b", "c"), children(this.from));

        assertEquals(List.of(), this.prepared);
    }

    @Test
    void movesIntoAParentThatKeepsNoOrder() throws RepositoryException
    {
        final Node folder = this.session.getRootNode().addNode("folder", "nt:folder");
        final Node file = this.from.addNode("file", "nt:folder");
        this.session.save();

        assertEquals("/folder/file", this.mover.move(file, folder, "file", null));
        assertEquals("/file", this.mover.move(this.session.getNode("/folder/file"), this.session.getRootNode(), "file",
            null));
        // Renamed where it is
        assertEquals("/renamed", this.mover.move(this.session.getNode("/file"), this.session.getRootNode(), "renamed",
            null));
    }

    private static List<String> children(final Node node) throws RepositoryException
    {
        final List<String> names = new ArrayList<>();
        for (final NodeIterator nodes = node.getNodes(); nodes.hasNext();) {
            names.add(nodes.nextNode().getName());
        }
        return names;
    }
}
