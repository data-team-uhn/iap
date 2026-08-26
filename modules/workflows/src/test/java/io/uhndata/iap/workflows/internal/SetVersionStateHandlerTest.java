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

import io.uhndata.iap.workflows.api.WorkflowConflictException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SetVersionStateHandler}: moving a version to the state its activity configures, refusing
 * the moves the lifecycle does not have, and refusing an activity that does not say which move it is.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SetVersionStateHandlerTest
{
    /** The node name of the version most of these tests act on. */
    private static final String FIRST = "1-0";

    private final SlingContext context = new SlingContext();

    private final SetVersionStateHandler handler = new SetVersionStateHandler();

    @BeforeEach
    void setUp()
    {
        AuthoringFixture.setUp(this.context);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(SetVersionStateHandler.NAME, this.handler.getName());
    }

    @Test
    void promotesADraft() throws WorkflowException, PersistenceException
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.DRAFT, Map.of());

        this.handler.execute(this.move(FIRST, "ACTIVE", new String[] { "DRAFT", "TRIAL" }));

        assertEquals(WorkflowVersion.State.ACTIVE, this.stateOf(FIRST));
    }

    @Test
    void promotesAVersionThatHasBeenOnTrial() throws WorkflowException, PersistenceException
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.TRIAL, Map.of());

        this.handler.execute(this.move(FIRST, "ACTIVE", new String[] { "DRAFT", "TRIAL" }));

        assertEquals(WorkflowVersion.State.ACTIVE, this.stateOf(FIRST));
    }

    @Test
    void putsADraftOnTrial() throws WorkflowException, PersistenceException
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.DRAFT, Map.of());

        this.handler.execute(this.move(FIRST, "TRIAL", new String[] { "DRAFT" }));

        assertEquals(WorkflowVersion.State.TRIAL, this.stateOf(FIRST));
    }

    @Test
    void returnsATrialToBeingADraft() throws WorkflowException, PersistenceException
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.TRIAL, Map.of());

        this.handler.execute(this.move(FIRST, "DRAFT", new String[] { "TRIAL" }));

        assertEquals(WorkflowVersion.State.DRAFT, this.stateOf(FIRST));
    }

    @Test
    void readsAConfigurationWrittenWithOneStateAndInAnyCase() throws WorkflowException, PersistenceException
    {
        // A JCR multiple-valued property authored with one entry reads back as a single value
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.TRIAL, Map.of());

        this.handler.execute(this.move(FIRST, "draft", "trial"));

        assertEquals(WorkflowVersion.State.DRAFT, this.stateOf(FIRST));
    }

    @Test
    void refusesAMoveTheVersionIsPastTheMomentFor()
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.RETIRED, Map.of());

        final WorkflowConflictException refusal = assertThrows(WorkflowConflictException.class,
            () -> this.handler.execute(this.move(FIRST, "ACTIVE", new String[] { "DRAFT", "TRIAL" })));
        assertTrue(refusal.getMessage().contains("A retired version cannot be made active"));
        // The message says which versions the move is for, since a stale page is the usual reason to see it
        assertTrue(refusal.getMessage().contains("draft or trial"));
        assertEquals(WorkflowVersion.State.RETIRED, this.stateOf(FIRST));
    }

    @Test
    void refusesAnActivityThatDoesNotSayWhereTheMoveGoes()
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.DRAFT, Map.of());
        final Activity misconfigured = AuthoringFixture.activity(this.context, "nowhere",
            Map.of("handler", SetVersionStateHandler.NAME, "fromStates", new String[] { "DRAFT" }));

        final WorkflowDefinitionException refusal = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(this.contextFor(FIRST, misconfigured)));
        assertTrue(refusal.getMessage().contains("does not name a toState"));
    }

    @Test
    void refusesAnActivityWhoseTargetStateIsNotOne()
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.DRAFT, Map.of());

        final WorkflowDefinitionException refusal = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(this.move(FIRST, "PUBLISHED", new String[] { "DRAFT" })));
        assertTrue(refusal.getMessage().contains("does not name a toState"));
    }

    @Test
    void refusesAnActivityThatDoesNotSayWhereTheMoveComesFrom()
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.DRAFT, Map.of());
        final Activity misconfigured = AuthoringFixture.activity(this.context, "unsourced",
            Map.of("handler", SetVersionStateHandler.NAME, "toState", "ACTIVE"));

        final WorkflowDefinitionException refusal = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(this.contextFor(FIRST, misconfigured)));
        assertTrue(refusal.getMessage().contains("does not list which fromStates"));
    }

    @Test
    void refusesAnActivityListingAStateThatIsNotOne()
    {
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.DRAFT, Map.of());

        final WorkflowDefinitionException refusal = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(this.move(FIRST, "ACTIVE", new String[] { "DRAFT", "PENDING" })));
        assertTrue(refusal.getMessage().contains("lists PENDING in fromStates"));
    }

    @Test
    void refusesATargetThatIsNotAVersion()
    {
        // A definition declaring the wrong targetResourceType is what reaches a handler with the wrong thing
        AuthoringFixture.createVersion(this.context, FIRST, "1.0", WorkflowVersion.State.DRAFT, Map.of());
        final Activity move = AuthoringFixture.activity(this.context, "move",
            Map.of("handler", SetVersionStateHandler.NAME, "toState", "ACTIVE",
                "fromStates", new String[] { "DRAFT" }));
        final WorkflowTaskContextImpl request = AuthoringFixture.context(
            AuthoringFixture.unreadable(this.context, AuthoringFixture.path(FIRST)),
            "activate", Map.of(), move, new HashMap<>());

        final WorkflowDefinitionException refusal =
            assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(request));
        assertTrue(refusal.getMessage().contains("is not one"));
    }

    /**
     * A task context moving one of the fixture's versions, with the activity configured for that move.
     *
     * @param name the version's node name
     * @param toState the state the move goes to
     * @param fromStates the states it is available from
     * @return the assembled context
     */
    private WorkflowTaskContextImpl move(final String name, final String toState, final Object fromStates)
    {
        return this.contextFor(name, AuthoringFixture.activity(this.context, "move-" + name + "-" + toState,
            Map.of("handler", SetVersionStateHandler.NAME, "toState", toState, "fromStates", fromStates)));
    }

    /**
     * A task context aimed at one of the fixture's versions.
     *
     * @param name the version's node name
     * @param activity the activity being performed
     * @return the assembled context
     */
    private WorkflowTaskContextImpl contextFor(final String name, final Activity activity)
    {
        return AuthoringFixture.context(this.context.resourceResolver().getResource(AuthoringFixture.path(name)),
            "activate", Map.of(), activity, new HashMap<>());
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
