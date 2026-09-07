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
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowVersion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RetireActiveVersionsHandler}: making room for a promotion by retiring whatever was
 * current, including the case where more than one version claims to be.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RetireActiveVersionsHandlerTest
{
    private final SlingContext context = new SlingContext();

    private final RetireActiveVersionsHandler handler = new RetireActiveVersionsHandler();

    private Activity activity;

    @BeforeEach
    void setUp()
    {
        AuthoringFixture.setUp(this.context);
        this.activity = AuthoringFixture.activity(this.context, "retire",
            Map.of("handler", RetireActiveVersionsHandler.NAME));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(RetireActiveVersionsHandler.NAME, this.handler.getName());
    }

    @Test
    void retiresTheOutgoingVersion() throws WorkflowException, PersistenceException
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", WorkflowVersion.State.ACTIVE, Map.of());
        AuthoringFixture.createVersion(this.context, "2-0", "2.0", WorkflowVersion.State.DRAFT, Map.of());
        final Map<String, Object> variables = new HashMap<>();

        this.handler.execute(this.retireFor("2-0", variables));

        assertEquals(WorkflowVersion.State.RETIRED, this.stateOf("1-0"));
        // The promoted version is left where it is; promoting it is the step after this one
        assertEquals(WorkflowVersion.State.DRAFT, this.stateOf("2-0"));
        assertArrayEquals(new String[] { AuthoringFixture.path("1-0") },
            (String[]) variables.get(RetireActiveVersionsHandler.RETIRED_VERSIONS));
    }

    @Test
    void retiresEveryVersionClaimingToBeCurrent() throws WorkflowException, PersistenceException
    {
        // More than one being active is already a broken invariant; a promotion is the moment to repair it
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", WorkflowVersion.State.ACTIVE, Map.of());
        AuthoringFixture.createVersion(this.context, "2-0", "2.0", WorkflowVersion.State.ACTIVE, Map.of());
        AuthoringFixture.createVersion(this.context, "3-0", "3.0", WorkflowVersion.State.TRIAL, Map.of());

        this.handler.execute(this.retireFor("3-0", new HashMap<>()));

        assertEquals(WorkflowVersion.State.RETIRED, this.stateOf("1-0"));
        assertEquals(WorkflowVersion.State.RETIRED, this.stateOf("2-0"));
    }

    @Test
    void retiresNothingWhenTheWorkflowHasNoActiveVersion() throws WorkflowException, PersistenceException
    {
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", WorkflowVersion.State.RETIRED, Map.of());
        AuthoringFixture.createVersion(this.context, "2-0", "2.0", WorkflowVersion.State.DRAFT, Map.of());
        final Map<String, Object> variables = new HashMap<>();

        this.handler.execute(this.retireFor("2-0", variables));

        assertEquals(WorkflowVersion.State.RETIRED, this.stateOf("1-0"));
        assertArrayEquals(new String[0],
            (String[]) variables.get(RetireActiveVersionsHandler.RETIRED_VERSIONS));
    }

    @Test
    void leavesAloneWhateverIsNotAVersion() throws WorkflowException, PersistenceException
    {
        // A definition's children need not all be versions, and adaptTo can return null if the bundle exposing
        // WorkflowVersion isn't fully started. Both are skipped rather than treated as active.
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", WorkflowVersion.State.DRAFT, Map.of());
        this.context.create().resource(AuthoringFixture.path("notes"),
            Map.of(WorkflowFixture.TYPE, "nt:unstructured", "state", "ACTIVE"));

        this.handler.execute(this.retireFor("1-0", new HashMap<>()));

        final Resource notes = this.context.resourceResolver().getResource(AuthoringFixture.path("notes"));
        assertNotNull(notes);
        assertEquals("ACTIVE", notes.getValueMap().get("state"));
    }

    @Test
    void refusesAVersionStoredOutsideAWorkflowDefinition()
    {
        // Nothing to retire and no telling what promoting it would make current
        this.context.create().resource("/loose/1-0", Map.of(
            WorkflowFixture.TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", "state", "DRAFT"));
        final WorkflowTaskContextImpl request = AuthoringFixture.context(
            this.context.resourceResolver().getResource("/loose/1-0"), "activate", Map.of(), this.activity,
            new HashMap<>());

        final WorkflowDefinitionException refusal =
            assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(request));
        assertTrue(refusal.getMessage().contains("not stored under a workflow definition"));
    }

    @Test
    void refusesAVersionStoredAtTheRoot()
    {
        // A resource with no parent at all: the same refusal, reached the other way
        final Resource orphan = this.context.create().resource("/orphan", Map.of(
            WorkflowFixture.TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0", "state", "DRAFT"));
        final Resource rootless = org.mockito.Mockito.spy(orphan);
        org.mockito.Mockito.doReturn(null).when(rootless).getParent();
        final WorkflowTaskContextImpl request =
            AuthoringFixture.context(rootless, "activate", Map.of(), this.activity, new HashMap<>());

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(request));
    }

    /**
     * A task context retiring whatever the fixture's definition has current, on behalf of one of its versions.
     *
     * @param name the node name of the version being promoted
     * @param variables where the handler reports what it retired
     * @return the assembled context
     */
    private WorkflowTaskContextImpl retireFor(final String name, final Map<String, Object> variables)
    {
        return AuthoringFixture.context(this.context.resourceResolver().getResource(AuthoringFixture.path(name)),
            "activate", Map.of(), this.activity, variables);
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
