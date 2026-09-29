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
package io.uhndata.iap.workflows.internal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.utils.internal.ContentMoverImpl;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link MoveContentHandler}: content moves only where its type is held, never into itself, within
 * the ancestor the activity keeps it in, and to the place the event asks.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MoveContentHandlerTest
{
    private static final String BOX = "/box";

    private static final String PARENT = "parent";

    private static final String BEFORE = "before";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final MoveContentHandler handler = new MoveContentHandler();

    private final Activity activity = Mockito.mock(Activity.class);

    private FieldsFixture fixture;

    @BeforeEach
    void setUp() throws RepositoryException, ReflectiveOperationException
    {
        this.fixture = new FieldsFixture(this.context);
        final Field mover = MoveContentHandler.class.getDeclaredField("mover");
        mover.setAccessible(true);
        mover.set(this.handler, new ContentMoverImpl());
        final Node root = this.fixture.session().getRootNode();
        for (final String name : List.of("a", "b", "c")) {
            item(root.getNode("box"), name);
        }
        item(root.getNode("shelf"), "loose");
        final Node first = root.addNode("first", "test:Crate");
        item(first.addNode("left", "test:Box"), "a");
        first.addNode("right", "test:Box");
        root.addNode("second", "test:Crate").addNode("other", "test:Box");
        this.fixture.session().save();
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(MoveContentHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void changesItsPlaceAmongItsSiblings() throws WorkflowException, PersistenceException, RepositoryException
    {
        assertEquals("/box/c", move("/box/c", Map.of(BEFORE, "a")));
        assertEquals(List.of("c", "a", "b"), this.fixture.children(BOX));

        move("/box/c", Map.of());
        assertEquals(List.of("a", "b", "c"), this.fixture.children(BOX));
    }

    @Test
    void numbersItAndItsSiblingsByTheirPlacesWhenAsked()
        throws WorkflowException, PersistenceException, RepositoryException
    {
        Mockito.when(this.activity.get(Placement.ORDER_PROPERTY, String.class)).thenReturn("position");

        move("/box/c", Map.of(BEFORE, "a"));
        assertEquals(List.of(10L, 20L, 30L), positions(List.of("c", "a", "b")));

        // Where nothing changes place, nothing is numbered again
        move("/box/c", Map.of(BEFORE, "a"));
        move("/box/a", Map.of());
        assertEquals(List.of(10L, 20L, 30L), positions(List.of("c", "b", "a")));
    }

    private List<Long> positions(final List<String> names) throws RepositoryException
    {
        final List<Long> positions = new ArrayList<>();
        for (final String name : names) {
            positions.add(this.fixture.session().getNode(BOX + "/" + name).getProperty("position").getLong());
        }
        return positions;
    }

    @Test
    void movesIntoAnotherParentThatHoldsItsType() throws WorkflowException, PersistenceException, RepositoryException
    {
        assertEquals("/box/loose", move("/shelf/loose", Map.of(PARENT, BOX, BEFORE, "b")));
        assertEquals(List.of("a", "loose", "b", "c"), this.fixture.children(BOX));

        // Its name is taken there
        assertEquals("/box/a2", move("/first/left/a", Map.of(PARENT, BOX)));
    }

    @Test
    void refusesWhereItCannotGo() throws RepositoryException
    {
        assertThrows(InvalidPayloadException.class, () -> move("/box/a", Map.of(PARENT, "/nowhere")));
        assertThrows(InvalidPayloadException.class, () -> move("/box/a", Map.of(PARENT, List.of(BOX))));
        assertThrows(InvalidPayloadException.class, () -> move("/box/a", Map.of(PARENT, "/box/a")));
        assertThrows(InvalidPayloadException.class, () -> move("/box", Map.of(PARENT, "/box/a")));
        // An item holds nothing, and a box holds no boxes
        assertThrows(InvalidPayloadException.class, () -> move("/box/a", Map.of(PARENT, "/item")));
        assertThrows(InvalidPayloadException.class, () -> move("/first/right", Map.of(PARENT, BOX)));
        assertThrows(InvalidPayloadException.class, () -> move("/box/a", Map.of(BEFORE, "none")));
        assertThrows(InvalidPayloadException.class, () -> move("/box/a", Map.of(BEFORE, 1)));
        // A shelf keeps no order
        assertThrows(InvalidPayloadException.class, () -> move("/box/a", Map.of(PARENT, "/shelf", BEFORE, "loose")));
        assertEquals(List.of("a", "b", "c"), this.fixture.children(BOX));
    }

    @Test
    void staysWithinWhatTheActivityKeepsItIn() throws WorkflowException, PersistenceException, RepositoryException
    {
        Mockito.when(this.activity.get(MoveContentHandler.WITHIN, String.class)).thenReturn("test/Crate");

        assertEquals("/first/right/a", move("/first/left/a", Map.of(PARENT, "/first/right")));
        assertThrows(InvalidPayloadException.class, () -> move("/first/right/a", Map.of(PARENT, "/second/other")));
        assertThrows(InvalidPayloadException.class, () -> move("/first/right/a", Map.of(PARENT, BOX)));
        // Not inside any crate to begin with
        assertThrows(InvalidPayloadException.class, () -> move("/box/a", Map.of(PARENT, "/first/left")));
    }

    @Test
    void checksOutWhatWasCheckedIn() throws WorkflowException, PersistenceException, RepositoryException
    {
        this.fixture.session().getWorkspace().getVersionManager().checkin(BOX);
        this.fixture.session().getWorkspace().getVersionManager().checkin("/first/left");

        move("/first/left/a", Map.of(PARENT, BOX, BEFORE, "a"));

        assertEquals(List.of("a2", "a", "b", "c"), this.fixture.children(BOX));
    }

    @Test
    void reportsContentThatCannotBeRead() throws RepositoryException
    {
        final Resource target = Mockito.mock(Resource.class);
        final Node node = Mockito.mock(Node.class);
        Mockito.when(target.adaptTo(Node.class)).thenReturn(node);
        Mockito.when(target.getPath()).thenReturn("/box/a");
        Mockito.when(target.getParent()).thenReturn(this.context.resourceResolver().getResource(BOX));
        Mockito.when(node.getPrimaryNodeType()).thenThrow(new RepositoryException("gone"));
        final WorkflowTaskContext task = this.fixture.task("move", this.activity, Map.of(), new HashMap<>());
        Mockito.when(task.getTarget()).thenReturn(target);

        assertThrows(PersistenceException.class, () -> this.handler.execute(task));
    }

    private String move(final String path, final Map<String, Object> payload)
        throws WorkflowException, PersistenceException, RepositoryException
    {
        final Map<String, Object> variables = new HashMap<>();
        final WorkflowTaskContext task = this.fixture.task("move", this.activity, payload, variables);
        Mockito.when(task.getTarget()).thenReturn(this.context.resourceResolver().getResource(path));
        this.handler.execute(task);
        this.fixture.session().save();
        return (String) variables.get(WorkflowResult.CREATED_PATH_VARIABLE);
    }

    private static void item(final Node parent, final String name) throws RepositoryException
    {
        parent.addNode(name, "test:Item").setProperty("title", name);
    }
}
