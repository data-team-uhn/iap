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
package io.uhndata.iap.workflows.internal.checks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.internal.PendingNotifications;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.EndEvent;
import io.uhndata.iap.workflows.models.FlowNode;
import io.uhndata.iap.workflows.models.IntermediateCatchingEvent;
import io.uhndata.iap.workflows.models.IntermediateThrowingEvent;
import io.uhndata.iap.workflows.models.StartEvent;
import io.uhndata.iap.workflows.models.WorkflowFixture;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ValidityCheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StartEventCheck}, {@link SystemWorkflowTriggerCheck}, {@link SystemWorkflowWaitCheck},
 * {@link MessageEventCheck}, {@link CatchingEventCheck} and {@link SubjectCheck}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkflowChecksTest
{
    private static final String SYSTEM_VERSION = "/SystemWorkflows/saveThing/v1";

    private static final String USER_VERSION = "/Workflows/approveThing/v1";

    private static final String MESSAGE_NAME = "messageName";

    private static final String WAITS = "a system workflow cannot wait, but it has a user task or a catching event";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
    }

    @Test
    void acceptsOneStartEvent()
    {
        final Resource version = version(USER_VERSION, null);
        start(version, "requested", null);

        assertEquals(List.of(), new StartEventCheck().check(version));
    }

    @Test
    void refusesNoStartEvent()
    {
        assertEquals(List.of("it needs exactly one start event, and has 0"),
            new StartEventCheck().check(version(USER_VERSION, null)));
    }

    @Test
    void refusesTwoStartEvents()
    {
        final Resource version = version(USER_VERSION, null);
        start(version, "first", "save");
        start(version, "second", "save");

        assertEquals(List.of("it needs exactly one start event, and has 2"), new StartEventCheck().check(version));
    }

    @Test
    void appliesToWorkflowVersionsOnly()
    {
        assertTrue(new StartEventCheck().appliesTo(version(USER_VERSION, null)));
        assertFalse(new StartEventCheck().appliesTo(this.context.create().resource("/Schemas/leave/v1",
            Map.of(WorkflowFixture.TYPE, "sch/SchemaVersion"))));
    }

    @Test
    void asksNothingMoreOfAUserWorkflow()
    {
        final Resource system = version(SYSTEM_VERSION, "sub/Submission");
        final Resource user = version(USER_VERSION, null);
        final Resource notes = this.context.create().resource("/SystemWorkflows/notes",
            Map.of(WorkflowFixture.TYPE, "nt:unstructured"));
        for (final ValidityCheck check : List.of(new SystemWorkflowTriggerCheck(), new SystemWorkflowWaitCheck())) {
            assertTrue(check.appliesTo(system));
            assertFalse(check.appliesTo(user));
            assertFalse(check.appliesTo(notes));
        }
    }

    @Test
    void acceptsASystemWorkflowThatSaysWhatTriggersIt()
    {
        final Resource version = version(SYSTEM_VERSION, "sub/Submission");
        start(version, "requested", "save");

        assertEquals(List.of(), new SystemWorkflowTriggerCheck().check(version));
    }

    @Test
    void refusesASystemWorkflowThatDoesNotSayWhatTriggersIt()
    {
        final Resource version = version(SYSTEM_VERSION, null);
        start(version, "requested", null);

        assertEquals(List.of("a system workflow must name the type it acts on in targetResourceType",
            "a system workflow must be started by a named message"),
            new SystemWorkflowTriggerCheck().check(version));
    }

    @Test
    void acceptsASystemWorkflowThatWaitsForNobody()
    {
        final Resource version = version(SYSTEM_VERSION, "sub/Submission");
        start(version, "requested", "save");

        assertEquals(List.of(), new SystemWorkflowWaitCheck().check(version));
    }

    @Test
    void refusesASystemWorkflowThatWaitsForAPerson()
    {
        final Resource version = version(SYSTEM_VERSION, "sub/Submission");
        start(version, "requested", "save");
        userTask(version);

        assertEquals(List.of(WAITS), new SystemWorkflowWaitCheck().check(version));
    }

    @Test
    void refusesASystemWorkflowThatWaitsForAMessage()
    {
        final Resource version = version(SYSTEM_VERSION, "sub/Submission");
        start(version, "requested", "save");
        node(version, "assessed", IntermediateCatchingEvent.RESOURCE_TYPE, Map.of(MESSAGE_NAME, "assessed"));

        assertEquals(List.of(WAITS), new SystemWorkflowWaitCheck().check(version));
    }

    @Test
    void acceptsCatchingEventsThatNameTheirMessage()
    {
        final Resource version = version(USER_VERSION, null);
        node(version, "assessed", IntermediateCatchingEvent.RESOURCE_TYPE, Map.of(MESSAGE_NAME, "assessed"));
        userTask(version);

        assertEquals(List.of(), new CatchingEventCheck().check(version));
    }

    @Test
    void refusesACatchingEventThatNamesNoMessage()
    {
        final Resource version = version(USER_VERSION, null);
        node(version, "assessed", IntermediateCatchingEvent.RESOURCE_TYPE, Map.of());

        assertEquals(List.of("the catching event assessed waits for nothing: it names no message"),
            new CatchingEventCheck().check(version));
    }

    @Test
    void acceptsMessageEventsThatNameTheirMessage()
    {
        final Resource version = version(USER_VERSION, null);
        node(version, "decided", EndEvent.RESOURCE_TYPE, Map.of(MESSAGE_NAME, "decided",
            PendingNotifications.RECIPIENTS_PROPERTY, new String[] {"@creator"}));
        node(version, "milestone", IntermediateThrowingEvent.RESOURCE_TYPE, Map.of());
        userTask(version);

        assertEquals(List.of(), new MessageEventCheck().check(version));
    }

    @Test
    void refusesMessageEventsThatSayWhoToTellButNameNoMessage()
    {
        final Resource version = version(USER_VERSION, null);
        node(version, "decided", EndEvent.RESOURCE_TYPE,
            Map.of(PendingNotifications.RECIPIENTS_PROPERTY, new String[] {"@creator"}));
        node(version, "submitted", IntermediateThrowingEvent.RESOURCE_TYPE,
            Map.of(PendingNotifications.TEMPLATE_PROPERTY, "/libs/iap/notificationTemplates/submitted"));

        assertEquals(List.of("the event decided says who to tell, but names no message",
            "the event submitted says who to tell, but names no message"),
            new MessageEventCheck().check(version));
    }

    @Test
    void acceptsTheSubjectsTheEngineKnows()
    {
        final Resource version = version(USER_VERSION, null);
        node(version, "tag", Activity.RESOURCE_TYPE, Map.of("subject", FlowNode.CREATED_SUBJECT));
        node(version, "decided", EndEvent.RESOURCE_TYPE, Map.of("subject", FlowNode.TARGET_SUBJECT));
        userTask(version);

        assertEquals(List.of(), new SubjectCheck().check(version));
    }

    @Test
    void refusesASubjectTheEngineDoesNotKnow()
    {
        final Resource version = version(USER_VERSION, null);
        node(version, "tag", Activity.RESOURCE_TYPE, Map.of("subject", "parent"));

        assertEquals(List.of("the node tag acts on parent, which is not one of created, target"),
            new SubjectCheck().check(version));
    }

    @Test
    void checksEveryWorkflowVersion()
    {
        final Resource version = version(USER_VERSION, null);
        final Resource notes = this.context.create().resource("/Workflows/notes",
            Map.of(WorkflowFixture.TYPE, "nt:unstructured"));
        for (final ValidityCheck check
            : List.of(new MessageEventCheck(), new CatchingEventCheck(), new SubjectCheck())) {
            assertTrue(check.appliesTo(version));
            assertFalse(check.appliesTo(notes));
        }
    }

    private Resource version(final String path, final String targetType)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(WorkflowFixture.TYPE, WorkflowVersion.RESOURCE_TYPE);
        properties.put("version", "1.0");
        if (targetType != null) {
            properties.put("targetResourceType", targetType);
        }
        return this.context.create().resource(path, properties);
    }

    private void start(final Resource version, final String name, final String messageName)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(WorkflowFixture.TYPE, StartEvent.RESOURCE_TYPE);
        properties.put("elementId", name);
        if (messageName != null) {
            properties.put(MESSAGE_NAME, messageName);
        }
        this.context.create().resource(version, name, properties);
    }

    private void node(final Resource version, final String name, final String type,
        final Map<String, Object> properties)
    {
        final Map<String, Object> node = new HashMap<>(properties);
        node.put(WorkflowFixture.TYPE, type);
        node.put("elementId", name);
        this.context.create().resource(version, name, node);
    }

    private void userTask(final Resource version)
    {
        this.context.create().resource(version, "review", Map.of(
            WorkflowFixture.TYPE, Activity.RESOURCE_TYPE, "elementId", "review"));
    }
}
