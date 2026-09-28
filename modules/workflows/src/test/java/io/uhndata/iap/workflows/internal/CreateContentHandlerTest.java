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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;

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

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
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

        assertEquals(List.of("titled", "noted", "item", "item2", "titled2", "item3"), children(BOX));
    }

    @Test
    void goesBeforeTheSiblingTheEventNames() throws WorkflowException, PersistenceException, RepositoryException
    {
        create(BOX, Map.of(TYPE, ITEM, "patch", "{\"title\": \"First\"}"));
        create(BOX, Map.of(TYPE, ITEM, "patch", "{\"title\": \"Last\"}"));

        create(BOX, Map.of(TYPE, ITEM, "before", "last", "patch", "{\"title\": \"Middle\"}"));

        assertEquals(List.of("first", "middle", "last"), children(BOX));
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
        assertEquals(List.of(), children(BOX));
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

        assertEquals(List.of("afterTheCheckin"), children(BOX));
    }

    @Test
    void reportsAParentThatCannotBeRead() throws RepositoryException
    {
        final Resource parent = Mockito.mock(Resource.class);
        final Node node = Mockito.mock(Node.class);
        Mockito.when(parent.adaptTo(Node.class)).thenReturn(node);
        Mockito.when(node.getMixinNodeTypes()).thenThrow(new RepositoryException("gone"));
        final WorkflowTaskContext task = context(BOX, Map.of(TYPE, ITEM), new HashMap<>());
        Mockito.when(task.getTarget()).thenReturn(parent);

        assertThrows(PersistenceException.class, () -> this.handler.execute(task));
    }

    private Map<String, Object> create(final String parent, final Map<String, Object> payload)
        throws WorkflowException, PersistenceException, RepositoryException
    {
        final Map<String, Object> variables = new HashMap<>();
        this.handler.execute(context(parent, payload, variables));
        // What a following update fills in, so that the new item can be saved
        final Node created =
            this.fixture.session().getNode((String) variables.get(WorkflowResult.CREATED_PATH_VARIABLE));
        created.setProperty("title", "Filled in");
        this.fixture.session().save();
        return variables;
    }

    private List<String> children(final String path) throws RepositoryException
    {
        final List<String> names = new ArrayList<>();
        for (final NodeIterator nodes = this.fixture.session().getNode(path).getNodes(); nodes.hasNext();) {
            names.add(nodes.nextNode().getName());
        }
        return names;
    }

    private WorkflowTaskContext context(final String parent, final Map<String, Object> payload,
        final Map<String, Object> variables)
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        final WorkflowTaskContext task = Mockito.mock(WorkflowTaskContext.class);
        Mockito.when(task.getEvent()).thenReturn(new WorkflowEvent("create", payload));
        Mockito.when(task.getActivity()).thenReturn(this.fixture.creating());
        Mockito.when(task.getResourceResolver()).thenReturn(resolver);
        Mockito.when(task.getTarget()).thenReturn(resolver.getResource(parent));
        Mockito.doAnswer(invocation -> variables.put(invocation.getArgument(0), invocation.getArgument(1)))
            .when(task).setVariable(Mockito.anyString(), Mockito.any());
        return task;
    }
}
