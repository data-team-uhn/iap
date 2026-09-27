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
package io.uhndata.iap.deletion.internal;

import java.lang.reflect.Field;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.deletion.api.DeletionException;
import io.uhndata.iap.deletion.api.DeletionImpact;
import io.uhndata.iap.deletion.api.DeletionOptions;
import io.uhndata.iap.deletion.api.DeletionResult;
import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.api.Veto;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

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
    private final DeleteTaskHandler handler = new DeleteTaskHandler();

    private final DeletionService deletionService = Mockito.mock(DeletionService.class);

    private final Resource target = Mockito.mock(Resource.class);

    private final Activity activity = Mockito.mock(Activity.class);

    private final WorkflowTaskContext context = Mockito.mock(WorkflowTaskContext.class);

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        final Field reference = DeleteTaskHandler.class.getDeclaredField("deletionService");
        reference.setAccessible(true);
        reference.set(this.handler, this.deletionService);
        Mockito.when(this.target.getPath()).thenReturn("/Schemas/intake");
        Mockito.when(this.context.getTarget()).thenReturn(this.target);
        Mockito.when(this.context.getActivity()).thenReturn(this.activity);
        Mockito.when(this.context.getActor()).thenReturn("coordinator");
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
    void refusesWhatSomethingElseRefersTo()
    {
        answer(DeletionResult.Status.REQUIRES_CONFIRMATION);

        final NoApplicableWorkflowException refusal =
            assertThrows(NoApplicableWorkflowException.class, () -> this.handler.execute(this.context));
        assertEquals("Referenced by 1 category (Intake)", refusal.getMessage());
    }

    @Test
    void refusesWhatAVetoProtects()
    {
        answer(DeletionResult.Status.VETOED);

        final NoApplicableWorkflowException refusal =
            assertThrows(NoApplicableWorkflowException.class, () -> this.handler.execute(this.context));
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
        Mockito.when(this.deletionService.delete(Mockito.any(), Mockito.any()))
            .thenThrow(new DeletionException("no service user", null));

        assertThrows(WorkflowFailedException.class, () -> this.handler.execute(this.context));
    }

    /**
     * Makes the deletion service answer with a status, and captures the options it was asked with.
     *
     * @param status the outcome
     * @return the captured options
     */
    private ArgumentCaptor<DeletionOptions> answer(final DeletionResult.Status status)
    {
        final DeletionImpact impact = Mockito.mock(DeletionImpact.class);
        Mockito.when(impact.getSummary()).thenReturn("Referenced by 1 category (Intake)");
        Mockito.when(impact.getVetoes()).thenReturn(List.of(
            new Veto("undeletable", "/Schemas/intake", "It is undeletable"),
            new Veto("undeletable", "/Schemas/intake/v1", "It is undeletable")));
        final ArgumentCaptor<DeletionOptions> options = ArgumentCaptor.forClass(DeletionOptions.class);
        Mockito.when(this.deletionService.delete(Mockito.eq(this.target), options.capture()))
            .thenReturn(new DeletionResult(status, null, impact));
        return options;
    }
}
