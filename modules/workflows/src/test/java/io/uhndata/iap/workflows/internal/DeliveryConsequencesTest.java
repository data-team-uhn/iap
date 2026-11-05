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

import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.notifications.api.NotificationService;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.FlowNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link DeliveryConsequences}: what a delivery leaves for its history and for after its commit.
 *
 * @version $Id$
 * @since 0.1.0
 */
class DeliveryConsequencesTest
{
    private static final String CHECKED_OUT = "/Requests/one";

    private static final WorkflowEvent EVENT = new WorkflowEvent("create", Map.of());

    private final DeliveryConsequences consequences =
        new DeliveryConsequences(new PendingNotifications(Mockito.mock(NotificationService.class)));

    private final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);

    private final Session session = Mockito.mock(Session.class);

    private final VersionManager versions = Mockito.mock(VersionManager.class);

    private final ErrorLoggerService errors = Mockito.mock(ErrorLoggerService.class);

    @BeforeEach
    void setUp() throws RepositoryException
    {
        final Workspace workspace = Mockito.mock(Workspace.class);
        Mockito.when(this.resolver.adaptTo(Session.class)).thenReturn(this.session);
        Mockito.when(this.session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getVersionManager()).thenReturn(this.versions);
        Mockito.when(this.session.nodeExists(CHECKED_OUT)).thenReturn(true);
        Mockito.when(this.versions.isCheckedOut(CHECKED_OUT)).thenReturn(true);
        ErrorLogger.setService(this.errors);
    }

    @AfterEach
    void tearDown()
    {
        ErrorLogger.unsetService(this.errors);
    }

    @Test
    void checksInWhatAStepCheckedOutOnceCommitted() throws RepositoryException
    {
        this.consequences.checkInAfterCommit(CHECKED_OUT);

        this.consequences.completeAfterCommit(this.resolver);

        Mockito.verify(this.versions).checkin(CHECKED_OUT);
    }

    @Test
    void takesAStepsRequestToCheckInThroughItsDelivery() throws RepositoryException
    {
        final ServiceTaskDispatcher dispatcher = new ServiceTaskDispatcher(List.of(), null, null, null,
            this.consequences);
        final WorkflowTaskContextImpl step = new WorkflowTaskContextImpl(null, EVENT, null, Map.of(), "jdoe",
            dispatcher, 0);

        step.checkInAfterCommit(CHECKED_OUT);
        dispatcher.completeAfterCommit(this.resolver);

        Mockito.verify(this.versions).checkin(CHECKED_OUT);
    }

    @Test
    void leavesAloneANodeTheDeliveryRemovedOrSomebodyCheckedIn() throws RepositoryException
    {
        Mockito.when(this.versions.isCheckedOut("/Requests/two")).thenReturn(false);
        this.consequences.checkInAfterCommit("/Requests/gone");
        this.consequences.checkInAfterCommit("/Requests/two");
        Mockito.when(this.session.nodeExists("/Requests/two")).thenReturn(true);

        this.consequences.completeAfterCommit(this.resolver);

        Mockito.verify(this.versions, Mockito.never()).checkin(Mockito.anyString());
    }

    @Test
    void reportsANodeItCannotCheckInAndGoesOnWithTheRest() throws RepositoryException
    {
        Mockito.when(this.session.nodeExists("/Requests/locked")).thenReturn(true);
        Mockito.when(this.versions.isCheckedOut("/Requests/locked")).thenReturn(true);
        Mockito.when(this.versions.checkin("/Requests/locked")).thenThrow(new RepositoryException("locked"));
        this.consequences.checkInAfterCommit("/Requests/locked");
        this.consequences.checkInAfterCommit(CHECKED_OUT);

        this.consequences.completeAfterCommit(this.resolver);

        Mockito.verify(this.errors).logError(Mockito.any(), Mockito.any());
        Mockito.verify(this.versions).checkin(CHECKED_OUT);
    }

    @Test
    void checksInNothingWithoutARepositorySession() throws RepositoryException
    {
        final ResourceResolver bare = Mockito.mock(ResourceResolver.class);
        this.consequences.checkInAfterCommit(CHECKED_OUT);

        this.consequences.completeAfterCommit(bare);

        Mockito.verify(this.versions, Mockito.never()).checkin(Mockito.anyString());
    }

    @Test
    void namesTheFirstInstanceTheDeliveryStarted()
    {
        this.consequences.noteStartedInstance("/Requests/one/wf:instances/first");
        this.consequences.noteStartedInstance("/Requests/one/wf:instances/second");

        assertEquals("/Requests/one/wf:instances/first",
            this.consequences.recordedAsSystemWorkflow("jdoe", EVENT, Mockito.mock(FlowNode.class))
                .getWorkflowInstance());
    }

    @Test
    void namesNoVersionForAStartEventStoredOutsideOne()
    {
        assertNull(this.consequences.recordedAsSystemWorkflow("jdoe", EVENT, Mockito.mock(FlowNode.class))
            .getWorkflowVersion());
    }
}
