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
package io.uhndata.iap.notifications.internal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.NotificationService;
import io.uhndata.iap.notifications.api.Recipient;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NotifyHandler}: that a workflow's own words — the template, the roles, the urgency — are what
 * reaches the notification service, and that nothing it can go wrong at fails the process.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class NotifyHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String VERSION = "/Workflows/timeOffRequest/v1";

    private final SlingContext context = new SlingContext();

    private final NotifyHandler handler = new NotifyHandler();

    /** What the service was asked to do, instead of doing it. */
    private final List<NotificationContext> raised = new ArrayList<>();

    private final List<List<String>> audiences = new ArrayList<>();

    private Resource submission;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, FlowNode.class, Activity.class);
        this.submission = this.context.create().resource("/Submissions/one",
            "title", "A request", "createdBy", "the-requester");
        final NotificationService service = (notification, roles) -> {
            this.raised.add(notification);
            this.audiences.add(roles);
            return List.of(new Recipient("the-requester", null, "requester@example.com"));
        };
        final Field field = NotifyHandler.class.getDeclaredField("notifications");
        field.setAccessible(true);
        field.set(this.handler, service);
    }

    /**
     * A notify task carrying the given settings, as a workflow definition writes them.
     *
     * @param settings what the node says
     * @return the task context to hand the handler
     */
    private WorkflowTaskContext taskWith(final Map<String, Object> settings)
    {
        final Map<String, Object> properties = new java.util.HashMap<>(settings);
        properties.put(TYPE, Activity.RESOURCE_TYPE);
        properties.put("sling:resourceSuperType", "wf/FlowNode");
        properties.put("elementId", "notifyApproved");
        final Resource node = this.context.create().resource(VERSION + "/notifyApproved", properties);
        final Activity activity = node.adaptTo(Activity.class);

        final WorkflowTaskContext task = Mockito.mock(WorkflowTaskContext.class);
        Mockito.when(task.getActivity()).thenReturn(activity);
        Mockito.when(task.getTarget()).thenReturn(this.submission);
        Mockito.when(task.getActor()).thenReturn("an-approver");
        Mockito.when(task.getResourceResolver()).thenReturn(this.context.resourceResolver());
        return task;
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(NotifyHandler.NAME, this.handler.getName());
    }

    // Everything that differs between one notification and the next is read off the node, which is what lets a
    // workflow author add one without writing Java
    @Test
    void raisesWhatTheWorkflowNodeSays() throws Exception
    {
        this.handler.execute(this.taskWith(Map.of(
            "handler", "notify",
            "event", "approved",
            "template", "/libs/iap/mailTemplates/timeOffApproved",
            "notify", new String[] { NotificationService.CREATOR_ROLE },
            "urgency", NotificationContext.IMMEDIATE)));

        assertEquals(1, this.raised.size());
        final NotificationContext notification = this.raised.get(0);
        assertEquals("approved", notification.getEvent());
        assertEquals("/libs/iap/mailTemplates/timeOffApproved", notification.getTemplate());
        assertEquals(NotificationContext.IMMEDIATE, notification.getUrgency());
        assertEquals(this.submission.getPath(), notification.getSubject().getPath());
        assertEquals("an-approver", notification.getActor());
        assertEquals(List.of(NotificationService.CREATOR_ROLE), this.audiences.get(0));
    }

    // Two nodes may report the same event with different wording, so the event is named rather than taken from
    // whatever the node happened to be called — but the node's own id is a sane fallback
    @Test
    void fallsBackOnTheNodesIdWhenNoEventIsNamed() throws Exception
    {
        this.handler.execute(this.taskWith(Map.of(
            "notify", new String[] { NotificationService.CREATOR_ROLE })));

        assertEquals("notifyApproved", this.raised.get(0).getEvent());
    }

    @Test
    void defaultsToImmediateWhenTheNodeDoesNotSay() throws Exception
    {
        this.handler.execute(this.taskWith(Map.of(
            "notify", new String[] { NotificationService.CREATOR_ROLE })));

        assertEquals(NotificationContext.IMMEDIATE, this.raised.get(0).getUrgency());
        assertNull(this.raised.get(0).getTemplate());
    }

    // A notify task that tells nobody is a definition somebody meant to finish, and raising a notification for
    // an empty audience would only hide that
    @Test
    void raisesNothingWhenTheNodeNamesNobody() throws Exception
    {
        this.handler.execute(this.taskWith(Map.of("event", "approved")));

        assertTrue(this.raised.isEmpty());
    }

    // The decision this reports has already been taken; undoing it because nobody could be told would be the
    // worse of the two outcomes
    @Test
    void doesNotFailTheWorkflowWhenNotifyingThrows() throws Exception
    {
        final Field field = NotifyHandler.class.getDeclaredField("notifications");
        field.setAccessible(true);
        field.set(this.handler, (NotificationService) (notification, roles) -> {
            throw new IllegalStateException("the mail server is on fire");
        });

        this.handler.execute(this.taskWith(Map.of(
            "notify", new String[] { NotificationService.CREATOR_ROLE })));
    }

}
