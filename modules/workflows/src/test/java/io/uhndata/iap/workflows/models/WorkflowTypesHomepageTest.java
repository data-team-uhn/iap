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
package io.uhndata.iap.workflows.models;

import java.util.List;
import java.util.Map;

import jakarta.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.autodoc.api.AutoDocumentable;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkflowTypesHomepage}, including the self-documentation the BPMN editor's toolbars are
 * built from.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkflowTypesHomepageTest
{
    private static final String PATH = "/WorkflowTypes";

    /** The heading the {@code wf:WorkflowTypesHomepage} node type autocreates. */
    private static final String TITLE = "Workflow node types";

    /** The introduction the {@code wf:WorkflowTypesHomepage} node type autocreates. */
    private static final String INTRO = "Everything a workflow can be built out of. Each entry says which BPMN "
        + "element it stands for, and how that element is stored once the workflow is parsed. Adding an entry here "
        + "is what makes a new kind of node available, both to the parser and in the editor's toolbars.";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
    }

    @Test
    void listsTheVocabularyOrderedByLabel()
    {
        final Resource resource = this.createVocabulary();

        final List<FlowNodeType> types = resource.adaptTo(WorkflowTypesHomepage.class).getFlowNodeTypes();

        assertEquals(3, types.size());
        assertEquals("End Event", types.get(0).getDocumentationLabel());
        assertEquals("Start Event", types.get(1).getDocumentationLabel());
        assertEquals("User Task", types.get(2).getDocumentationLabel());
    }

    @Test
    void looksAnEntryUpByName()
    {
        final Resource resource = this.createVocabulary();
        final WorkflowTypesHomepage homepage = resource.adaptTo(WorkflowTypesHomepage.class);

        assertNotNull(homepage.getFlowNodeType("UserTask"));
        assertEquals("User Task", homepage.getFlowNodeType("UserTask").getDocumentationLabel());
        assertNull(homepage.getFlowNodeType("NoSuchType"));
    }

    @Test
    void doesNotMistakeAnUnrelatedChildForAVocabularyEntry()
    {
        // The node type accepts arbitrary children for extensibility, and an access control policy can appear here
        // too; without a type check either would adapt to a FlowNodeType whose label is null
        final Resource resource = this.createVocabulary();
        this.context.create().resource(PATH + "/notes", Map.of(TYPE, "nt:unstructured"));
        final WorkflowTypesHomepage homepage = resource.adaptTo(WorkflowTypesHomepage.class);

        assertNull(homepage.getFlowNodeType("notes"));
        assertEquals(3, homepage.getFlowNodeTypes().size());
    }

    @Test
    void servesTheCatalogueDespiteAMalformedEntry()
    {
        // An entry stored as a plain node carrying a vocabulary resource type gets past the mandatory properties of
        // the real node type. It must not take the catalogue with it: the editor's toolbars are built from this
        final Resource resource = this.createVocabulary();
        this.context.create().resource(PATH + "/Malformed", Map.of(TYPE, ActivityType.RESOURCE_TYPE));
        final WorkflowTypesHomepage homepage = resource.adaptTo(WorkflowTypesHomepage.class);

        final List<FlowNodeType> types = homepage.getFlowNodeTypes();

        assertEquals(4, types.size());
        // Ordered by label, and the malformed entry sorts under the node name it falls back on
        assertEquals("Malformed", types.get(1).getDocumentationLabel());
        // It lands in the group its kind implies, alongside the user task, rather than in none
        assertEquals(2, homepage.toDocumentationJson().getJsonObject("items")
            .getJsonArray("Activities").size());
    }

    @Test
    void ignoresAnEntryOfTheAbstractVocabularyType()
    {
        // wf:FlowNodeType is abstract, so an entry carrying it as its own type is of no kind at all; it matches no
        // model, and would otherwise arrive as whichever implementation sorts first
        final Resource resource = this.createVocabulary();
        this.context.create().resource(PATH + "/Abstract", Map.of(
            TYPE, FlowNodeType.RESOURCE_TYPE, "label", "Abstract", "priority", 0L));
        final WorkflowTypesHomepage homepage = resource.adaptTo(WorkflowTypesHomepage.class);

        assertEquals(3, homepage.getFlowNodeTypes().size());
        // The lookup by name has to agree with the listing, or an entry would be reachable one way but not the other
        assertNull(homepage.getFlowNodeType("Abstract"));
    }

    @Test
    void listsNothingWhenTheVocabularyIsEmpty()
    {
        final Resource resource = this.context.create().resource(PATH, TYPE,
            WorkflowTypesHomepage.RESOURCE_TYPE);

        assertTrue(resource.adaptTo(WorkflowTypesHomepage.class).getFlowNodeTypes().isEmpty());
    }

    @Test
    void documentsItself()
    {
        final Resource resource = this.createVocabulary();
        final WorkflowTypesHomepage homepage = resource.adaptTo(WorkflowTypesHomepage.class);

        assertEquals(TITLE, homepage.getDocumentationTitle());
        assertEquals(INTRO, homepage.getDocumentationIntro());
        assertEquals(3, homepage.getDocumentedItems().size());
    }

    @Test
    void headingsCanBeReworded()
    {
        // A deployment can reword the heading by editing the autocreated properties, and nothing in the model
        // second-guesses what it stored
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowTypesHomepage.RESOURCE_TYPE,
            "title", "Building blocks",
            "description", "The pieces a process can be drawn from."));

        final WorkflowTypesHomepage homepage = resource.adaptTo(WorkflowTypesHomepage.class);

        assertEquals("Building blocks", homepage.getDocumentationTitle());
        assertEquals("The pieces a process can be drawn from.", homepage.getDocumentationIntro());
    }

    @Test
    void servesTheCatalogueGroupedByToolbarCategory()
    {
        final Resource resource = this.createVocabulary();

        final JsonObject json = resource.adaptTo(WorkflowTypesHomepage.class).toDocumentationJson();

        assertEquals("Workflow node types", json.getString("title"));
        final JsonObject items = json.getJsonObject("items");
        assertEquals(3, items.size());
        assertEquals(1, items.getJsonArray("Activities").size());
        assertEquals(1, items.getJsonArray("Start Events").size());
        assertEquals(1, items.getJsonArray("End Events").size());
        assertEquals("bpmn:userTask",
            items.getJsonArray("Activities").getJsonObject(0).getString("xmlElement"));
    }

    @Test
    void isAdaptableAsAnAutoDocumentable()
    {
        // This is what the documentation servlet asks for, rather than for the concrete model
        final Resource resource = this.createVocabulary();

        final AutoDocumentable documentable = resource.adaptTo(AutoDocumentable.class);

        assertNotNull(documentable);
        assertEquals(WorkflowTypesHomepage.class, documentable.getClass());
    }

    private Resource createVocabulary()
    {
        // The heading properties are autocreated from the defaults declared by the wf:WorkflowTypesHomepage node
        // type, which the mock repository does not apply, so the test sets the very values the CND declares
        final Resource resource = this.context.create().resource(PATH, Map.of(
            TYPE, WorkflowTypesHomepage.RESOURCE_TYPE,
            "title", TITLE,
            "description", INTRO));
        this.context.create().resource(PATH + "/UserTask", Map.of(
            TYPE, ActivityType.RESOURCE_TYPE, "label", "User Task", "priority", 0L,
            "xmlElement", "bpmn:userTask", "jcrNodeType", "wf:Activity"));
        this.context.create().resource(PATH + "/StartEvent", Map.of(
            TYPE, CatchingEventType.RESOURCE_TYPE, "label", "Start Event", "priority", 0L,
            "category", new String[] {"Start Events"},
            "xmlElement", "bpmn:startEvent", "jcrNodeType", "wf:StartEvent"));
        this.context.create().resource(PATH + "/EndEvent", Map.of(
            TYPE, ThrowingEventType.RESOURCE_TYPE, "label", "End Event", "priority", 0L,
            "category", new String[] {"End Events"},
            "xmlElement", "bpmn:endEvent", "jcrNodeType", "wf:EndEvent"));
        return resource;
    }
}
