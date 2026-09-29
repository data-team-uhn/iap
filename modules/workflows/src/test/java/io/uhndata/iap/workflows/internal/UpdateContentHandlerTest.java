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
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

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
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link UpdateContentHandler}: a patch applied to the fields the activity lists and the content's
 * type declares, checked whole before anything is written.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class UpdateContentHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final UpdateContentHandler handler = new UpdateContentHandler();

    private FieldsFixture fixture;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.fixture = new FieldsFixture(this.context);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(UpdateContentHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void changesTheListedFields() throws WorkflowException, PersistenceException, RepositoryException
    {
        update("{\"title\": \" Renamed \", \"note\": \"A note\"}");

        assertEquals("Renamed", this.fixture.item().getProperty("title").getString());
        assertEquals("A note", this.fixture.item().getProperty("note").getString());

        update("{\"note\": null}");
        assertFalse(this.fixture.item().hasProperty("note"));
        update("{\"note\": \" \"}");
        assertFalse(this.fixture.item().hasProperty("note"));
    }

    @Test
    void pointsAReferenceAtWhatAPathNames() throws WorkflowException, PersistenceException, RepositoryException
    {
        update("{\"link\": \"/other\"}");

        assertEquals(this.fixture.other().getIdentifier(), this.fixture.item().getProperty("link").getString());
        this.fixture.other().addNode("part", "test:Item").setProperty("title", "A part of another item");
        this.fixture.session().save();
        update("{\"weakLink\": \"/other/part\", \"link\": \"\"}");
        assertEquals(PropertyType.WEAKREFERENCE, this.fixture.item().getProperty("weakLink").getType());
        assertFalse(this.fixture.item().hasProperty("link"));
        // Only what is under the field's root
        assertThrows(InvalidPayloadException.class, () -> update("{\"weakLink\": \"/other\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"weakLink\": \"/item\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"link\": \"/nowhere\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"link\": \"/update\"}"));
    }

    @Test
    void setsNumbersAndTruthValues() throws WorkflowException, PersistenceException, RepositoryException
    {
        update("{\"shape\": \"square\", \"count\": 3, \"ratio\": 0.5, \"flag\": true}");

        final Node item = this.fixture.item();
        assertEquals(PropertyType.LONG, item.getProperty("count").getType());
        assertEquals(3, item.getProperty("count").getLong());
        assertEquals(0.5, item.getProperty("ratio").getDouble());
        assertTrue(item.getProperty("flag").getBoolean());
        update("{\"flag\": false, \"ratio\": 2}");
        assertFalse(item.getProperty("flag").getBoolean());
        assertEquals(2.0, item.getProperty("ratio").getDouble());
    }

    @Test
    void refusesValuesOfTheWrongKind()
    {
        assertThrows(InvalidPayloadException.class, () -> update("{\"shape\": \"round\", \"count\": \"3\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"shape\": \"round\", \"count\": 1.5}"));
        assertThrows(InvalidPayloadException.class,
            () -> update("{\"shape\": \"round\", \"count\": 99999999999999999999}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"shape\": \"square\", \"ratio\": \"x\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"flag\": \"yes\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"note\": [\"x\"]}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"keywords\": \"x\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"sizes\": [\"x\"]}"));
        // Declared, but of a kind no patch can set
        assertThrows(InvalidPayloadException.class, () -> update("{\"due\": \"2026-09-27\"}"));
    }

    @Test
    void keepsToTheChoices() throws WorkflowException, PersistenceException, RepositoryException
    {
        update("{\"shape\": \"round\"}");
        assertEquals("round", this.fixture.item().getProperty("shape").getString());
        update("{\"shape\": \"square\"}");
        assertEquals("square", this.fixture.item().getProperty("shape").getString());

        assertThrows(InvalidPayloadException.class, () -> update("{\"shape\": \"squareChoice\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"shape\": \"triangle\"}"));
    }

    @Test
    void keepsAUniqueFieldApartFromItsSiblings() throws WorkflowException, PersistenceException, RepositoryException
    {
        final Node fields = this.fixture.session().getNode("/update/fields");
        fields.getNode("title").setProperty("unique", true);
        fields.getNode("keywords").setProperty("unique", true);
        this.fixture.other().setProperty("keywords", new String[] { "red", "blue" });
        this.fixture.session().save();

        // What the other item is called already
        assertThrows(InvalidPayloadException.class, () -> update("{\"title\": \"Another item\"}"));
        // Only what is taken is said to be
        assertEquals("Something else here already has blue as its keywords", assertThrows(
            InvalidPayloadException.class, () -> update("{\"keywords\": [\"green\", \"blue\"]}")).getMessage());
        update("{\"title\": \"A third item\", \"keywords\": [\"green\"]}");
        assertEquals("A third item", this.fixture.item().getProperty("title").getString());
        // Its own value, and none at all, repeat nothing
        update("{\"title\": \"A third item\", \"keywords\": null}");
        assertFalse(this.fixture.item().hasProperty("keywords"));
    }

    @Test
    void setsOnlyTheFieldsThatApply() throws WorkflowException, PersistenceException, RepositoryException
    {
        assertThrows(InvalidPayloadException.class, () -> update("{\"count\": 3}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"shape\": \"round\", \"ratio\": 1}"));

        update("{\"count\": 3, \"shape\": \"round\"}");
        assertEquals(3, this.fixture.item().getProperty("count").getLong());
        // Clearing a field that does not apply is not refused
        update("{\"ratio\": null}");
    }

    @Test
    void removesTheFieldsThatStopApplying() throws WorkflowException, PersistenceException, RepositoryException
    {
        update("{\"shape\": \"square\", \"count\": 3, \"ratio\": 0.5}");

        update("{\"shape\": \"round\"}");
        assertTrue(this.fixture.item().hasProperty("count"));
        assertFalse(this.fixture.item().hasProperty("ratio"));
        update("{\"shape\": null}");
        assertFalse(this.fixture.item().hasProperty("count"));
    }

    @Test
    void keepsAMandatoryFieldThatStopsApplying() throws WorkflowException, PersistenceException, RepositoryException
    {
        final Node applicability =
            this.fixture.session().getNode("/update/fields/title").addNode("appliesWhen", "nt:unstructured");
        applicability.setProperty("property", "keywords");
        applicability.setProperty("values", new String[] { "titled" });
        this.fixture.session().save();

        update("{\"keywords\": [\"untitled\"]}");
        update("{\"note\": \"x\"}");

        assertEquals("The item", this.fixture.item().getProperty("title").getString());
        assertThrows(InvalidPayloadException.class, () -> update("{\"title\": \"Renamed\"}"));
        update("{\"keywords\": [\"untitled\", \"titled\"], \"title\": \"Renamed\"}");
        assertEquals("Renamed", this.fixture.item().getProperty("title").getString());
    }

    @Test
    void appliesNowhereWhenTheDependencyIsNotNamed() throws RepositoryException
    {
        this.fixture.session().getNode("/update/fields/note").addNode("appliesWhen", "nt:unstructured");
        this.fixture.session().save();

        assertThrows(InvalidPayloadException.class, () -> update("{\"note\": \"x\"}"));
    }

    @Test
    void setsSeveralValues() throws WorkflowException, PersistenceException, RepositoryException
    {
        update("{\"keywords\": [\" a \", \"\", \"b\"], \"sizes\": [2, 1],"
            + " \"related\": [\"/other\", \"/item\"]}");

        final Node item = this.fixture.item();
        assertEquals(List.of("a", "b"), strings(item.getProperty("keywords").getValues()));
        assertEquals(PropertyType.LONG, item.getProperty("sizes").getType());
        assertEquals(List.of("2", "1"), strings(item.getProperty("sizes").getValues()));
        assertEquals(List.of(this.fixture.other().getIdentifier(), item.getIdentifier()),
            strings(item.getProperty("related").getValues()));
        update("{\"keywords\": [], \"sizes\": null}");
        assertFalse(item.hasProperty("keywords"));
        assertFalse(item.hasProperty("sizes"));
    }

    @Test
    void refusesWhatThePatchCannotChange() throws RepositoryException
    {
        assertThrows(InvalidPayloadException.class, () -> update("{\"title\": null}"));
        // Listed, but only allowed by the type's residual definition
        assertThrows(InvalidPayloadException.class, () -> update("{\"extra\": \"x\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"unlisted\": \"x\"}"));
        assertThrows(InvalidPayloadException.class, () -> update("{\"note\": 3}"));
        // Nothing of a refused patch is applied
        assertThrows(InvalidPayloadException.class, () -> update("{\"note\": \"Kept?\", \"title\": \"\"}"));
        assertFalse(this.fixture.item().hasProperty("note"));
    }

    @Test
    void refusesToLeaveNewContentWithoutAMandatoryField() throws RepositoryException
    {
        this.fixture.session().getNode("/box").addNode("fresh", "test:Item");
        final WorkflowTaskContext task = context(Map.of("patch", "{\"note\": \"No title\"}"));
        Mockito.when(task.getTarget()).thenReturn(this.context.resourceResolver().getResource("/box/fresh"));

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(task));
    }

    @Test
    void refusesAMissingOrMalformedPatch()
    {
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(context(Map.of())));
        assertThrows(InvalidPayloadException.class, () -> update("[1, 2]"));
        assertThrows(InvalidPayloadException.class, () -> update("{"));
    }

    @Test
    void needsTheActivityToListTheFields()
    {
        Mockito.when(this.fixture.activity().getChild("fields", Content.class)).thenReturn(null);

        assertThrows(WorkflowDefinitionException.class, () -> update("{\"title\": \"x\"}"));
    }

    @Test
    void checksOutContentThatWasCheckedIn() throws WorkflowException, PersistenceException, RepositoryException
    {
        this.fixture.session().getWorkspace().getVersionManager().checkin("/item");

        update("{\"note\": \"After the checkin\"}");

        assertEquals("After the checkin", this.fixture.item().getProperty("note").getString());
    }

    @Test
    void reportsContentThatCannotBeWritten() throws RepositoryException
    {
        final Resource host = Mockito.mock(Resource.class);
        final Node node = Mockito.mock(Node.class);
        Mockito.when(host.adaptTo(Node.class)).thenReturn(node);
        Mockito.when(node.getMixinNodeTypes()).thenThrow(new RepositoryException("gone"));
        final WorkflowTaskContext task = context(Map.of("patch", "{}"));
        Mockito.when(task.getTarget()).thenReturn(host);

        assertThrows(PersistenceException.class, () -> this.handler.execute(task));
    }

    private static List<String> strings(final Value[] values) throws RepositoryException
    {
        final List<String> strings = new ArrayList<>();
        for (final Value value : values) {
            strings.add(value.getString());
        }
        return strings;
    }

    private void update(final String patch) throws WorkflowException, PersistenceException, RepositoryException
    {
        this.handler.execute(context(Map.of("patch", patch)));
        this.fixture.session().save();
    }

    private WorkflowTaskContext context(final Map<String, Object> payload)
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        final WorkflowTaskContext task = Mockito.mock(WorkflowTaskContext.class);
        Mockito.when(task.getEvent()).thenReturn(new WorkflowEvent("update", payload));
        Mockito.when(task.getActivity()).thenReturn(this.fixture.activity());
        Mockito.when(task.getResourceResolver()).thenReturn(resolver);
        Mockito.when(task.getTarget()).thenReturn(resolver.getResource("/item"));
        return task;
    }
}
