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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowConflictException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DraftVersionHandler}: carrying a version forward as a new draft, with its diagram, and
 * with its graph only where nothing will derive one.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DraftVersionHandlerTest
{
    /** The node name of the version most of these tests act on. */
    private static final String FIRST = "1-0";

    private final SlingContext context = new SlingContext();

    private final DraftVersionHandler handler = new DraftVersionHandler();

    private Activity activity;

    @BeforeEach
    void setUp()
    {
        AuthoringFixture.setUp(this.context);
        this.activity = AuthoringFixture.activity(this.context, "draft",
            Map.of("handler", DraftVersionHandler.NAME));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(DraftVersionHandler.NAME, this.handler.getName());
    }

    @Test
    void copiesAnActiveVersionIntoANewDraft() throws WorkflowException, PersistenceException, IOException
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.ACTIVE,
            Map.of("description", "The one in use", "bpmnXmlParsedHash", "abc123"));
        AuthoringFixture.loadDiagram(this.context, FIRST);
        final Map<String, Object> variables = new HashMap<>();

        this.handler.execute(this.draft(FIRST, Map.of("version", "2.0"), variables));

        assertEquals(AuthoringFixture.path("2-0"), variables.get(WorkflowResult.CREATED_PATH));
        final Resource draft = this.context.resourceResolver().getResource(AuthoringFixture.path("2-0"));
        assertNotNull(draft);
        assertEquals("2.0", draft.getValueMap().get("version"));
        assertEquals("DRAFT", draft.getValueMap().get("state"));
        assertEquals("The one in use", draft.getValueMap().get("description"));
        assertEquals(AuthoringFixture.BPMN, AuthoringFixture.read(draft.getChild("bpmn.xml")));
        // Never copied: a draft must not claim a parse that has not happened for it
        assertNull(draft.getValueMap().get("bpmnXmlParsedHash"));
        // The source is untouched: drafting from a version is not a move
        assertEquals(WorkflowVersion.State.ACTIVE, this.stateOf(FIRST));
    }

    @Test
    void takesANewDescriptionWhenOneIsGiven() throws WorkflowException, PersistenceException
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.ACTIVE,
            Map.of("description", "The one in use", "targetResourceType", "wf/WorkflowsHomepage"));

        this.handler.execute(this.draft(FIRST, Map.of("version", "2.0", "description", "  A fresh take  "),
            new HashMap<>()));

        final Resource draft = this.context.resourceResolver().getResource(AuthoringFixture.path("2-0"));
        assertNotNull(draft);
        assertEquals("A fresh take", draft.getValueMap().get("description"));
        // A draft of a system workflow answers for the same events as the version it came from
        assertEquals("wf/WorkflowsHomepage", draft.getValueMap().get("targetResourceType"));
    }

    @Test
    void copiesNeitherDescriptionNorGraphWhenThereIsNone() throws WorkflowException, PersistenceException
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.DRAFT,
            Map.of("bpmnAuthoritative", true));

        this.handler.execute(this.draft(FIRST, Map.of("version", "2.0"), new HashMap<>()));

        final Resource draft = this.context.resourceResolver().getResource(AuthoringFixture.path("2-0"));
        assertNotNull(draft);
        assertNull(draft.getValueMap().get("description"));
        assertNull(draft.getValueMap().get("targetResourceType"));
        // Nothing to copy: a version with no diagram yet drafts into one with none either
        assertNull(draft.getChild("bpmn.xml"));
        assertEquals(Boolean.TRUE, draft.getValueMap().get("bpmnAuthoritative", Boolean.class));
    }

    @Test
    void leavesTheGraphOffADiagramOwnedVersion() throws WorkflowException, PersistenceException
    {
        // The commit editor derives the whole tree from the copied diagram, in the same commit, so a copied tree
        // would only be waiting to be replaced by the identical one
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.ACTIVE,
            Map.of("bpmnAuthoritative", true));
        AuthoringFixture.loadDiagram(this.context, FIRST);
        this.context.create().resource(AuthoringFixture.path(FIRST) + "/start", Map.of(
            WorkflowFixture.TYPE, "wf/StartEvent", "elementId", "start"));

        this.handler.execute(this.draft(FIRST, Map.of("version", "2.0"), new HashMap<>()));

        final Resource draft = this.context.resourceResolver().getResource(AuthoringFixture.path("2-0"));
        assertNotNull(draft);
        assertNull(draft.getChild("start"));
        assertNotNull(draft.getChild("bpmn.xml"));
    }

    @Test
    void carriesAHandAuthoredGraphForwardBecauseNothingWillDeriveIt()
        throws WorkflowException, PersistenceException
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.ACTIVE, Map.of());
        this.context.create().resource(AuthoringFixture.path(FIRST) + "/start", Map.of(
            WorkflowFixture.TYPE, "wf/StartEvent", "elementId", "start", "messageName", "create",
            "performers", new String[] { "iap-administrators" }));
        this.context.create().resource(AuthoringFixture.path(FIRST) + "/start/toEnd", Map.of(
            WorkflowFixture.TYPE, "wf/SequenceFlow", "elementId", "toEnd", "targetRef", "end"));

        this.handler.execute(this.draft(FIRST, Map.of("version", "2.0"), new HashMap<>()));

        final Resource copied =
            this.context.resourceResolver().getResource(AuthoringFixture.path("2-0") + "/start");
        assertNotNull(copied);
        assertEquals("create", copied.getValueMap().get("messageName"));
        // Nested as flow nodes nest: an arc is a child of the node it leaves
        final Resource arc = copied.getChild("toEnd");
        assertNotNull(arc);
        assertEquals("end", arc.getValueMap().get("targetRef"));
    }

    @Test
    void copiesTheGraphAndNothingElseTheVersionHolds() throws WorkflowException, PersistenceException
    {
        // link:links is autocreated on every version, so copying the source's over the draft's own would collide.
        // The same reasoning excludes anything else stored beside the graph — only the process itself is copied.
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.ACTIVE, Map.of());
        this.context.create().resource(AuthoringFixture.path(FIRST) + "/link:links",
            Map.of(WorkflowFixture.TYPE, "link/Links"));
        this.context.create().resource(AuthoringFixture.path(FIRST) + "/notes",
            Map.of(WorkflowFixture.TYPE, "nt:unstructured", "memo", "not part of the process"));
        this.context.create().resource(AuthoringFixture.path(FIRST) + "/start", Map.of(
            WorkflowFixture.TYPE, "wf/StartEvent", "elementId", "start"));

        this.handler.execute(this.draft(FIRST, Map.of("version", "2.0"), new HashMap<>()));

        final Resource draft = this.context.resourceResolver().getResource(AuthoringFixture.path("2-0"));
        assertNotNull(draft);
        assertNotNull(draft.getChild("start"));
        assertNull(draft.getChild("link:links"));
        assertNull(draft.getChild("notes"));
    }

    @Test
    void findsAFreeNodeNameWhenTheDerivedOneIsTaken() throws WorkflowException, PersistenceException
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.ACTIVE, Map.of());
        AuthoringFixture.createVersion(this.context, "2-0", "2/0", WorkflowVersion.State.DRAFT, Map.of());

        this.handler.execute(this.draft(FIRST, Map.of("version", "2.0"), new HashMap<>()));

        assertNotNull(this.context.resourceResolver().getResource(AuthoringFixture.path("2-0-2")));
    }

    @Test
    void refusesALabelTheWorkflowAlreadyCarries()
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.ACTIVE, Map.of());

        final WorkflowConflictException refusal = assertThrows(WorkflowConflictException.class,
            () -> this.handler.execute(this.draft(FIRST, Map.of("version", "1.0"), new HashMap<>())));
        assertTrue(refusal.getMessage().contains("already has a version 1.0"));
    }

    @Test
    void requiresALabel()
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.ACTIVE, Map.of());

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(this.draft(FIRST, Map.of(), new HashMap<>())));
        assertTrue(refusal.getMessage().contains("version is required"));
    }

    @Test
    void refusesAVersionThatCannotBeRead()
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.ACTIVE, Map.of());
        final WorkflowTaskContextImpl request = AuthoringFixture.context(
            AuthoringFixture.unreadable(this.context, AuthoringFixture.path(FIRST)), "draft",
            Map.of("version", "2.0"), this.activity, new HashMap<>());

        assertThrows(WorkflowException.class, () -> this.handler.execute(request));
    }

    @Test
    void reportsADiagramThatCannotBeRead()
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.ACTIVE, Map.of());
        AuthoringFixture.loadDiagram(this.context, FIRST);
        final WorkflowTaskContextImpl request = AuthoringFixture.context(
            AuthoringFixture.withUnreadableDiagram(this.context, AuthoringFixture.path(FIRST)), "draft",
            Map.of("version", "2.0"), this.activity, new HashMap<>());

        final PersistenceException failure =
            assertThrows(PersistenceException.class, () -> this.handler.execute(request));
        assertTrue(failure.getMessage().contains("The stream broke"));
    }

    /**
     * A task context drafting from one of the fixture's versions.
     *
     * @param name the source version's node name
     * @param payload what the event carries
     * @param variables where the handler reports its results
     * @return the assembled context
     */
    private WorkflowTaskContextImpl draft(final String name, final Map<String, Object> payload,
        final Map<String, Object> variables)
    {
        return AuthoringFixture.context(this.context.resourceResolver().getResource(AuthoringFixture.path(name)),
            "draft", payload, this.activity, variables);
    }

    /**
     * The lifecycle state a version currently carries.
     *
     * @param name the version's node name
     * @return its state
     */
    private WorkflowVersion.State stateOf(final String name)
    {
        final Resource resource = this.context.resourceResolver().getResource(AuthoringFixture.path(name));
        assertNotNull(resource);
        final WorkflowVersion version = resource.adaptTo(WorkflowVersion.class);
        assertNotNull(version);
        return version.getState();
    }
}
