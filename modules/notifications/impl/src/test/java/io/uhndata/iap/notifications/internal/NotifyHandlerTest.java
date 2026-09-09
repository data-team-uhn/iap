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
import java.util.HashMap;
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
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NotifyHandler}. What reaches the notification service is the workflow's own words: the
 * template, the roles, the urgency. Nothing the handler can fail at fails the process.
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
        return taskWith(settings, Map.of());
    }

    /**
     * A notify task carrying the given settings, reached by an event with the given payload.
     *
     * @param settings what the node says
     * @param payload what the event that got here was carrying
     * @return the task context to hand the handler
     */
    private WorkflowTaskContext taskWith(final Map<String, Object> settings, final Map<String, Object> payload)
    {
        final Map<String, Object> properties = new HashMap<>(settings);
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
        Mockito.when(task.getEvent()).thenReturn(new WorkflowEvent("complete", payload));
        return task;
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(NotifyHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void raisesWhatTheWorkflowNodeSays() throws Exception
    {
        this.handler.execute(this.taskWith(Map.of(
            "handler", "notify",
            "event", "approved",
            "template", "/libs/iap/notificationTemplates/timeOffApproved",
            "notify", new String[] { PrincipalService.CREATOR },
            "urgency", NotificationContext.IMMEDIATE)));

        assertEquals(1, this.raised.size());
        final NotificationContext notification = this.raised.get(0);
        assertEquals("approved", notification.getEvent());
        assertEquals("/libs/iap/notificationTemplates/timeOffApproved", notification.getTemplate());
        assertEquals(NotificationContext.IMMEDIATE, notification.getUrgency());
        assertEquals(this.submission.getPath(), notification.getSubject().getPath());
        assertEquals("an-approver", notification.getActor());
        assertEquals(List.of(PrincipalService.CREATOR), this.audiences.get(0));
    }

    @Test
    void carriesTheDecisionAndItsReasonToTheWording() throws Exception
    {
        this.handler.execute(this.taskWith(
            Map.of("event", "rejected", "notify", new String[] { PrincipalService.CREATOR }),
            Map.of("outcome", "rejected", "outcomeNote", "We are short-staffed that week")));

        final Map<String, Object> variables = this.raised.get(0).getVariables();
        assertEquals("rejected", variables.get("outcome"));
        assertEquals("We are short-staffed that week", variables.get("outcomeNote"));
    }

    @Test
    void leavesOutADecisionNoteThatWasNotGiven() throws Exception
    {
        this.handler.execute(this.taskWith(
            Map.of("event", "approved", "notify", new String[] { PrincipalService.CREATOR }),
            Map.of("outcome", "approved", "outcomeNote", "   ", "unrelated", 7)));

        final Map<String, Object> variables = this.raised.get(0).getVariables();
        assertEquals("approved", variables.get("outcome"));
        assertFalse(variables.containsKey("outcomeNote"));
        // The handler names which entries travel; the rest of a payload is not the wording's business
        assertFalse(variables.containsKey("unrelated"));
    }

    @Test
    void fallsBackOnTheNodesIdWhenNoEventIsNamed() throws Exception
    {
        this.handler.execute(this.taskWith(Map.of(
            "notify", new String[] { PrincipalService.CREATOR })));

        assertEquals("notifyApproved", this.raised.get(0).getEvent());
    }

    @Test
    void defaultsToImmediateWhenTheNodeDoesNotSay() throws Exception
    {
        this.handler.execute(this.taskWith(Map.of(
            "notify", new String[] { PrincipalService.CREATOR })));

        assertEquals(NotificationContext.IMMEDIATE, this.raised.get(0).getUrgency());
        assertNull(this.raised.get(0).getTemplate());
    }

    @Test
    void raisesNothingWhenTheNodeNamesNobody() throws Exception
    {
        this.handler.execute(this.taskWith(Map.of("event", "approved")));

        assertTrue(this.raised.isEmpty());
    }

    @Test
    void doesNotFailTheWorkflowWhenNotifyingThrows() throws Exception
    {
        final Field field = NotifyHandler.class.getDeclaredField("notifications");
        field.setAccessible(true);
        field.set(this.handler, (NotificationService) (notification, roles) -> {
            throw new IllegalStateException("the mail server is on fire");
        });

        this.handler.execute(this.taskWith(Map.of(
            "notify", new String[] { PrincipalService.CREATOR })));
    }
}
