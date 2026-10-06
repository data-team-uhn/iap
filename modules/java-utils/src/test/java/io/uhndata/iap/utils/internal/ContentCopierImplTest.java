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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.utils.copy.CopyParticipant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ContentCopierImpl}: content copied whatever its structure, with references pointed at the
 * copies, what the repository maintains left out, and the participants consulted.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ContentCopierImplTest
{
    private static final String UNSTRUCTURED = "nt:unstructured";

    private static final String REFERENCEABLE = "mix:referenceable";

    private static final byte[] DATA = "Some content".getBytes(StandardCharsets.UTF_8);

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final ContentCopierImpl copier = new ContentCopierImpl();

    private final List<String> adjusted = new ArrayList<>();

    private Session session;

    private Node source;

    private Node target;

    private Node outside;

    @BeforeEach
    void setUp() throws RepositoryException, ReflectiveOperationException
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        final Node root = this.session.getRootNode();
        this.outside = referenceable(root, "outside");
        this.source = referenceable(root, "source");
        this.source.addMixin("mix:lastModified");
        this.source.setProperty("jcr:lastModified", new GregorianCalendar(2020, Calendar.JANUARY, 1));
        this.source.setProperty("title", "The source");
        this.source.setProperty("label", "1.0");
        this.source.setProperty("tags", new String[] { "active", "reviewed" });
        this.source.setProperty("computed", "maintained elsewhere");
        this.source.setProperty("workflow", this.outside);
        final Node first = referenceable(this.source, "first");
        first.setProperty("label", "Kept below the root");
        first.setProperty("tags", new String[] { "active" });
        first.addMixin("mix:title");
        final Node second = this.source.addNode("second", UNSTRUCTURED);
        second.setProperty("dependsOn", first);
        final ValueFactory values = this.session.getValueFactory();
        second.setProperty("weakly", values.createValue(first, true));
        second.setProperty("related", new Value[] { values.createValue(first), values.createValue(this.outside) });
        this.source.setProperty("links", new Value[] { values.createValue(first), values.createValue(this.outside) });
        this.source.addNode("maintained", UNSTRUCTURED).setProperty("kept", false);
        this.source.addNode("existing", UNSTRUCTURED).setProperty("fromSource", true);
        final Node file = this.source.addNode("file", "nt:file");
        file.addNode("jcr:content", "nt:resource").setProperty("jcr:data",
            this.session.getValueFactory().createBinary(new ByteArrayInputStream(DATA)));

        this.target = root.addNode("target", UNSTRUCTURED);
        this.target.setProperty("label", "2.0");
        this.target.addNode("existing", UNSTRUCTURED).setProperty("fromTarget", true);
        this.session.save();

        final Field field = ContentCopierImpl.class.getDeclaredField("participants");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        final List<CopyParticipant> participants = (List<CopyParticipant>) field.get(this.copier);
        participants.add(new CopyParticipant()
        {
        });
        participants.add(new CopyParticipant()
        {
            @Override
            public boolean skips(final Property property) throws RepositoryException
            {
                return "computed".equals(property.getName());
            }

            @Override
            public boolean skips(final Node child) throws RepositoryException
            {
                return "maintained".equals(child.getName());
            }

            @Override
            public void afterCopy(final Node from, final Node copy, final Map<String, String> identifiers)
                throws RepositoryException
            {
                ContentCopierImplTest.this.adjusted.add(from.getName() + " > " + copy.getName());
            }
        });
    }

    @Test
    void copiesPropertiesAndChildrenInOrder() throws RepositoryException
    {
        copy();

        assertEquals("The source", this.target.getProperty("title").getString());
        assertArrayEquals(new String[] { "existing", "first", "second", "file" }, childNames(this.target));
        assertTrue(this.target.getNode("first").isNodeType("mix:title"));
        assertEquals("Kept below the root", this.target.getNode("first").getProperty("label").getString());
        // Values are only dropped on the node copied itself
        assertEquals(1, this.target.getNode("first").getProperty("tags").getValues().length);
        assertNotEquals(this.source.getNode("first").getIdentifier(), this.target.getNode("first").getIdentifier());
    }

    @Test
    void leavesOutWhatTheRootSkipsAndDrops() throws RepositoryException
    {
        copy();

        assertEquals("2.0", this.target.getProperty("label").getString());
        final Value[] tags = this.target.getProperty("tags").getValues();
        assertEquals(1, tags.length);
        assertEquals("reviewed", tags[0].getString());
    }

    @Test
    void leavesOutWhatTheRepositoryMaintains() throws RepositoryException
    {
        copy();

        assertFalse(this.target.hasProperty("jcr:lastModified"));
        assertFalse(this.target.isNodeType(REFERENCEABLE));
    }

    @Test
    void fillsInChildrenTheTargetAlreadyHas() throws RepositoryException
    {
        copy();

        final Node existing = this.target.getNode("existing");
        assertTrue(existing.getProperty("fromSource").getBoolean());
        assertTrue(existing.getProperty("fromTarget").getBoolean());
    }

    @Test
    void pointsReferencesInsideTheCopyAtTheCopies() throws RepositoryException
    {
        final Map<String, String> identifiers = copy();

        final String first = this.target.getNode("first").getIdentifier();
        assertEquals(first, identifiers.get(this.source.getNode("first").getIdentifier()));
        final Node second = this.target.getNode("second");
        assertEquals(first, second.getProperty("dependsOn").getString());
        assertEquals(PropertyType.WEAKREFERENCE, second.getProperty("weakly").getType());
        assertEquals(first, second.getProperty("weakly").getString());
        final Value[] related = second.getProperty("related").getValues();
        assertEquals(first, related[0].getString());
        assertEquals(this.outside.getIdentifier(), related[1].getString());
        assertEquals(this.outside.getIdentifier(), this.target.getProperty("workflow").getString());
    }

    @Test
    void dropsValuesOfTheRootsReferencesToo() throws RepositoryException
    {
        copy();

        // The root's own reference to the outside node is dropped, and what is left still points at the copy
        final Value[] links = this.target.getProperty("links").getValues();
        assertEquals(1, links.length);
        assertEquals(this.target.getNode("first").getIdentifier(), links[0].getString());
        assertEquals(PropertyType.REFERENCE, this.target.getProperty("links").getType());
    }

    @Test
    void consultsTheParticipants() throws RepositoryException
    {
        copy();

        assertFalse(this.target.hasProperty("computed"));
        assertFalse(this.target.hasNode("maintained"));
        assertEquals(List.of("source > target"), this.adjusted);
    }

    @Test
    void copiesBinaries() throws RepositoryException, IOException
    {
        copy();

        try (InputStream data = this.target.getNode("file/jcr:content").getProperty("jcr:data").getBinary()
            .getStream()) {
            assertArrayEquals(DATA, data.readAllBytes());
        }
    }

    private Map<String, String> copy() throws RepositoryException
    {
        final Map<String, String> identifiers = this.copier.copy(this.source, this.target, Set.of("label"),
            Map.of("tags", Set.of("active"), "links", Set.of(this.outside.getIdentifier())));
        this.session.save();
        return identifiers;
    }

    private static Node referenceable(final Node parent, final String name) throws RepositoryException
    {
        final Node node = parent.addNode(name, UNSTRUCTURED);
        node.addMixin(REFERENCEABLE);
        return node;
    }

    private static String[] childNames(final Node node) throws RepositoryException
    {
        final NodeIterator children = node.getNodes();
        final String[] names = new String[(int) children.getSize()];
        for (int i = 0; children.hasNext(); i++) {
            names[i] = children.nextNode().getName();
        }
        return names;
    }
}
