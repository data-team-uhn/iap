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
package io.uhndata.iap.deletion.internal.handlers;

import java.lang.reflect.Field;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionImpact;
import io.uhndata.iap.deletion.api.DeletionOptions;
import io.uhndata.iap.deletion.api.DeletionResult;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.Veto;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;
import io.uhndata.iap.workflows.testing.RecordingTaskContext;
import io.uhndata.iap.workflows.testing.RecordingTaskContext.RecordedRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DeleteTaskHandler}: deleting a workflow's target through the deletion service, and turning
 * what the service refuses into the engine's refusals.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DeleteTaskHandlerTest
{
    private static final String TARGET_PATH = "/Schemas/intake";

    private final DeleteTaskHandler handler = new DeleteTaskHandler();

    private final DeletionService deletionService = Mockito.mock(DeletionService.class);

    private final Resource target = Mockito.mock(Resource.class);

    private final Activity activity = Mockito.mock(Activity.class);

    private final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

    private final RecordingTaskContext context = RecordingTaskContext.aimedAt(this.target)
        .actingFor("coordinator")
        .performing(this.activity)
        .resolvingThrough(this.resolver)
        .build();

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        final Field reference = DeleteTaskHandler.class.getDeclaredField("deletionService");
        reference.setAccessible(true);
        reference.set(this.handler, this.deletionService);
        Mockito.when(this.target.getPath()).thenReturn(TARGET_PATH);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("delete", this.handler.getName());
    }

    @Test
    void archivesTheTarget() throws Exception
    {
        final ArgumentCaptor<DeletionOptions> options = answer(DeletionResult.Status.ARCHIVED);

        this.handler.execute(this.context);

        assertFalse(options.getValue().isPermanent());
        assertFalse(options.getValue().isRecursive());
        assertEquals("coordinator", options.getValue().getOnBehalfOf());
    }

    @Test
    void removesTheTargetForGoodWhenTheActivitySaysSo() throws Exception
    {
        Mockito.when(this.activity.get("permanent")).thenReturn(true);
        final ArgumentCaptor<DeletionOptions> options = answer(DeletionResult.Status.DELETED);

        this.handler.execute(this.context);

        assertTrue(options.getValue().isPermanent());
    }

    @Test
    void namesTheTargetArchivedForTheHistoryWhileItIsStillThere() throws Exception
    {
        answer(DeletionResult.Status.ARCHIVED);

        final WorkflowTaskContext watched = Mockito.spy(this.context);

        this.handler.execute(watched);

        final InOrder order = Mockito.inOrder(watched, this.deletionService);
        order.verify(watched).recordRole(this.target, "archived");
        order.verify(this.deletionService).deleteWithin(Mockito.eq(this.target), Mockito.any());
    }

    @Test
    void checksInWhatTheDeletionCheckedOutWhetherTheDeliveryCommitsOrNot() throws Exception
    {
        answer(DeletionResult.Status.ARCHIVED, List.of("/Schemas", "/Schemas/other"));

        this.handler.execute(this.context);

        assertEquals(2, this.context.getAfterCommitWork().size());
        assertEquals(this.context.getAfterCommitWork(), this.context.getRollbackActions());
    }

    @Test
    void checksInTheNodeWhenTheDeliveryIsOver() throws Exception
    {
        answer(DeletionResult.Status.ARCHIVED, List.of("/Schemas"));
        final VersionManager versions = checkedOutInTheRepository("/Schemas");

        this.handler.execute(this.context);
        registeredCheckIn().run();

        Mockito.verify(versions).checkin("/Schemas");
    }

    @Test
    void failsForTheEngineToRecordWhenTheCheckInFails() throws Exception
    {
        answer(DeletionResult.Status.ARCHIVED, List.of("/Schemas"));
        final VersionManager versions = checkedOutInTheRepository("/Schemas");
        Mockito.when(versions.checkin("/Schemas")).thenThrow(new RepositoryException("locked"));

        this.handler.execute(this.context);
        final Runnable checkIn = registeredCheckIn();

        final IllegalStateException failure = assertThrows(IllegalStateException.class, checkIn::run);
        assertTrue(failure.getMessage().contains("/Schemas"), failure.getMessage());
    }

    @Test
    void checksInNothingWhenTheDeletionCheckedNothingOut() throws Exception
    {
        answer(DeletionResult.Status.ARCHIVED);

        this.handler.execute(this.context);

        assertEquals(List.of(), this.context.getAfterCommitWork());
        assertEquals(List.of(), this.context.getRollbackActions());
    }

    @Test
    void namesTheTargetErasedForTheHistoryWhenRemovedForGood() throws Exception
    {
        Mockito.when(this.activity.get("permanent")).thenReturn(true);
        answer(DeletionResult.Status.DELETED);

        this.handler.execute(this.context);

        assertEquals(List.of(new RecordedRole(TARGET_PATH, "erased")), this.context.getRoles());
    }

    @Test
    void refusesWhatSomethingElseRefersTo()
    {
        answer(DeletionResult.Status.REQUIRES_CONFIRMATION);

        final InvalidStateException refusal =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(this.context));
        assertEquals("Referenced by 1 category (Intake)", refusal.getMessage());
    }

    @Test
    void refusesWhatAVetoProtects()
    {
        answer(DeletionResult.Status.VETOED);

        final InvalidStateException refusal =
            assertThrows(InvalidStateException.class, () -> this.handler.execute(this.context));
        assertEquals("It is undeletable", refusal.getMessage());
    }

    @Test
    void refusesWhatTheWorkflowMayNotDelete()
    {
        answer(DeletionResult.Status.DENIED);

        assertThrows(NotAuthorizedException.class, () -> this.handler.execute(this.context));
    }

    @Test
    void failsWhenTheDeletionServiceFails()
    {
        Mockito.when(this.deletionService.deleteWithin(Mockito.any(), Mockito.any()))
            .thenThrow(new DeletionException("no service user", null));

        assertThrows(WorkflowFailedException.class, () -> this.handler.execute(this.context));
    }

    /**
     * Gives the step a JCR session in which a node exists and is checked out.
     *
     * @param path the absolute path of the checked-out node
     * @return the version manager the check-in goes through
     */
    private VersionManager checkedOutInTheRepository(final String path) throws RepositoryException
    {
        final Session session = Mockito.mock(Session.class);
        final Workspace workspace = Mockito.mock(Workspace.class);
        final VersionManager versions = Mockito.mock(VersionManager.class);
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(session);
        Mockito.when(session.getWorkspace()).thenReturn(workspace);
        Mockito.when(session.nodeExists(path)).thenReturn(true);
        Mockito.when(workspace.getVersionManager()).thenReturn(versions);
        Mockito.when(versions.isCheckedOut(path)).thenReturn(true);
        return versions;
    }

    private Runnable registeredCheckIn()
    {
        final List<Runnable> checkIn = this.context.getAfterCommitWork();
        assertEquals(1, checkIn.size());
        return checkIn.get(0);
    }

    /**
     * Makes the deletion service answer with a status, and captures the options it was asked with.
     *
     * @param status the outcome
     * @return the captured options
     */
    private ArgumentCaptor<DeletionOptions> answer(final DeletionResult.Status status)
    {
        return answer(status, List.of());
    }

    /**
     * Makes the deletion service answer with a status, leaving some nodes checked out, and captures the options it
     * was asked with.
     *
     * @param status the outcome
     * @param checkedOut the nodes the deletion left checked out
     * @return the captured options
     */
    private ArgumentCaptor<DeletionOptions> answer(final DeletionResult.Status status, final List<String> checkedOut)
    {
        final DeletionImpact impact = Mockito.mock(DeletionImpact.class);
        Mockito.when(impact.getSummary()).thenReturn("Referenced by 1 category (Intake)");
        Mockito.when(impact.getVetoes()).thenReturn(List.of(
            new Veto("undeletable", "/Schemas/intake", "It is undeletable"),
            new Veto("undeletable", "/Schemas/intake/v1", "It is undeletable")));
        final ArgumentCaptor<DeletionOptions> options = ArgumentCaptor.forClass(DeletionOptions.class);
        Mockito.when(this.deletionService.deleteWithin(Mockito.eq(this.target), options.capture()))
            .thenReturn(new DeletionResult(status, null, impact, checkedOut));
        return options;
    }
}
