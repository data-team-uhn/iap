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

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link CreateContentHandler}: new content is of a type the activity lists and the parent accepts,
 * named after what it says, and placed where the event asks.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CreateContentHandlerTest
{
    private static final String ITEM = "test:Item";

    private static final String BOX = "/box";

    private static final String TYPE = "type";

    private static final String NAME = "name";

    private static final String PATCH = "patch";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final CreateContentHandler handler = new CreateContentHandler();

    private FieldsFixture fixture;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.fixture = new FieldsFixture(this.context);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(CreateContentHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void createsAListedTypeTheParentAccepts() throws WorkflowException, PersistenceException, RepositoryException
    {
        final Map<String, Object> variables = create(BOX, Map.of(TYPE, ITEM,
            "patch", "{\"title\": \"The first item of a great many, in order\"}"));

        assertEquals("/box/theFirstItemOfA", variables.get(WorkflowResult.CREATED_PATH_VARIABLE));
        assertEquals(ITEM, this.fixture.session().getNode("/box/theFirstItemOfA").getPrimaryNodeType().getName());
    }

    @Test
    void isNamedAfterTheFirstFieldThatNamesIt() throws WorkflowException, PersistenceException, RepositoryException
    {
        create(BOX, Map.of(TYPE, ITEM, "patch", "{\"note\": \" - \", \"title\": \"Titled\", \"count\": 3}"));
        create(BOX, Map.of(TYPE, ITEM, "patch", "{\"note\": \"(Noted)\", \"title\": \"Titled\"}"));
        create(BOX, Map.of(TYPE, ITEM, "patch", "{\"title\": 3}"));
        create(BOX, Map.of(TYPE, ITEM));
        create(BOX, Map.of(TYPE, ITEM, "patch", "{\"title\": \"Titled\"}"));
        Mockito.when(this.fixture.creating().get("nameFrom", String[].class)).thenReturn(null);
        create(BOX, Map.of(TYPE, ITEM, "patch", "{\"title\": \"Titled\"}"));

        assertEquals(List.of("titled", "noted", "item", "item2", "titled2", "item3"), this.fixture.children(BOX));
    }

    @Test
    void takesTheNameAskedFor() throws WorkflowException, PersistenceException, RepositoryException
    {
        create(BOX, Map.of(TYPE, ITEM, NAME, "chosen", PATCH, "{\"title\": \"Titled\"}"));
        // A blank one is no name asked for
        create(BOX, Map.of(TYPE, ITEM, NAME, " ", PATCH, "{\"title\": \"Titled\"}"));

        assertEquals(List.of("chosen", "titled"), this.fixture.children(BOX));
        // One asked for is taken as asked, or not at all
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, ITEM, NAME, "chosen")));
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, ITEM, NAME, "x/y")));
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, ITEM, NAME, List.of("x"))));
    }

    @Test
    void keepsToTheActivitysPattern() throws WorkflowException, PersistenceException, RepositoryException
    {
        Mockito.when(this.fixture.creating().get(ContentNames.NAME_PATTERN, String.class))
            .thenReturn("^[A-Za-z][A-Za-z0-9]*$");

        create(BOX, Map.of(TYPE, ITEM, PATCH, "{\"title\": \"Plain\"}"));
        // What it says would not make a name the pattern allows, so it is named after its type
        create(BOX, Map.of(TYPE, ITEM, PATCH, "{\"title\": \"2 things\"}"));
        // Its accents go
        create(BOX, Map.of(TYPE, ITEM, PATCH, "{\"title\": \"\u00c2ge\"}"));
        create(BOX, Map.of(TYPE, ITEM, NAME, "second2"));

        assertEquals(List.of("plain", "item", "age", "second2"), this.fixture.children(BOX));
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, ITEM, NAME, "2nd")));
    }

    @Test
    void followsWhatTheTypeSaysAboutItsName() throws WorkflowException, PersistenceException, RepositoryException
    {
        Mockito.when(this.fixture.creating().get(ContentNames.NAME_PATTERN, String.class)).thenReturn("^[a-z]+$");
        final Node item = this.fixture.session().getNode("/create/types/item");
        item.setProperty(ContentNames.NAME_PATTERN, "^[a-z0-9]+$");
        this.fixture.session().save();

        create(BOX, Map.of(TYPE, ITEM, NAME, "item2"));
        assertEquals(List.of("item2"), this.fixture.children(BOX));

        item.setProperty(ContentNames.NAMED, false);
        this.fixture.session().save();
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, ITEM, NAME, "chosen")));
        // What it says still names it, within its pattern
        create(BOX, Map.of(TYPE, ITEM, PATCH, "{\"title\": \"Titled\"}"));
        assertEquals(List.of("item2", "titled"), this.fixture.children(BOX));
    }

    @Test
    void goesBeforeTheSiblingTheEventNames() throws WorkflowException, PersistenceException, RepositoryException
    {
        create(BOX, Map.of(TYPE, ITEM, "patch", "{\"title\": \"First\"}"));
        create(BOX, Map.of(TYPE, ITEM, "patch", "{\"title\": \"Last\"}"));

        create(BOX, Map.of(TYPE, ITEM, "before", "last", "patch", "{\"title\": \"Middle\"}"));

        assertEquals(List.of("first", "middle", "last"), this.fixture.children(BOX));
    }

    @Test
    void numbersWhatItCreatesByItsPlaceWhenAsked()
        throws WorkflowException, PersistenceException, RepositoryException
    {
        // Asked for by the activity, unless the type says otherwise
        Mockito.when(this.fixture.creating().get(Placement.ORDER_PROPERTY, String.class)).thenReturn("rank");
        create(BOX, Map.of(TYPE, ITEM, "patch", "{\"title\": \"Last\"}"));
        assertEquals(10L, this.fixture.session().getProperty("/box/last/rank").getLong());

        this.fixture.session().getNode("/create/types/item").setProperty(Placement.ORDER_PROPERTY, "position");
        this.fixture.session().save();
        create(BOX, Map.of(TYPE, ITEM, "patch", "{\"title\": \"Last\"}"));
        create(BOX, Map.of(TYPE, ITEM, "before", "last", "patch", "{\"title\": \"First\"}"));

        assertEquals(List.of("first", "last", "last2"), this.fixture.children(BOX));
        assertEquals(10L, this.fixture.session().getProperty("/box/first/position").getLong());
        assertEquals(20L, this.fixture.session().getProperty("/box/last/position").getLong());
        assertEquals(30L, this.fixture.session().getProperty("/box/last2/position").getLong());
    }

    @Test
    void refusesWhatCannotBeCreatedHere() throws WorkflowException, PersistenceException, RepositoryException
    {
        create("/shelf", Map.of(TYPE, ITEM, "patch", "{\"title\": \"Loose\"}"));

        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of()));
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, List.of(ITEM))));
        // Not listed, or listed but not something a box holds
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, "nt:unstructured")));
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, "test:Box")));
        // Listed, but not a type anything can be
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, "data:Content")));
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, "test:Ghost")));
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, ITEM, "before", "none")));
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, ITEM, "before", 1)));
        // A shelf keeps no order
        assertThrows(InvalidPayloadException.class, () -> create("/shelf", Map.of(TYPE, ITEM, "before", "loose")));
        assertThrows(InvalidPayloadException.class, () -> create(BOX, Map.of(TYPE, ITEM, "patch", "{")));
        assertEquals(List.of(), this.fixture.children(BOX));
    }

    @Test
    void needsTheActivityToListTheTypes()
    {
        Mockito.when(this.fixture.creating().getChild("types", Content.class)).thenReturn(null);

        assertThrows(WorkflowDefinitionException.class, () -> create(BOX, Map.of(TYPE, ITEM)));
    }

    @Test
    void checksOutAParentThatWasCheckedIn() throws WorkflowException, PersistenceException, RepositoryException
    {
        this.fixture.session().getWorkspace().getVersionManager().checkin(BOX);

        create(BOX, Map.of(TYPE, ITEM, "patch", "{\"title\": \"After the checkin\"}"));

        assertEquals(List.of("afterTheCheckin"), this.fixture.children(BOX));
    }

    @Test
    void reportsAParentThatCannotBeRead() throws RepositoryException
    {
        final Resource parent = Mockito.mock(Resource.class);
        final Node node = Mockito.mock(Node.class);
        Mockito.when(parent.adaptTo(Node.class)).thenReturn(node);
        Mockito.when(node.getMixinNodeTypes()).thenThrow(new RepositoryException("gone"));
        Mockito.when(node.getSession()).thenThrow(new RepositoryException("gone"));
        final WorkflowTaskContext task = task(BOX, Map.of(TYPE, ITEM), new HashMap<>());
        Mockito.when(task.getTarget()).thenReturn(parent);

        assertThrows(PersistenceException.class, () -> this.handler.execute(task));
    }

    private WorkflowTaskContext task(final String parent, final Map<String, Object> payload,
        final Map<String, Object> variables)
    {
        final WorkflowTaskContext task = this.fixture.task("create", this.fixture.creating(), payload, variables);
        Mockito.when(task.getTarget()).thenReturn(this.context.resourceResolver().getResource(parent));
        return task;
    }

    private Map<String, Object> create(final String parent, final Map<String, Object> payload)
        throws WorkflowException, PersistenceException, RepositoryException
    {
        final Map<String, Object> variables = new HashMap<>();
        this.handler.execute(task(parent, payload, variables));
        // What a following update fills in, so that the new item can be saved
        final Node created =
            this.fixture.session().getNode((String) variables.get(WorkflowResult.CREATED_PATH_VARIABLE));
        created.setProperty("title", "Filled in");
        this.fixture.session().save();
        return variables;
    }
}
