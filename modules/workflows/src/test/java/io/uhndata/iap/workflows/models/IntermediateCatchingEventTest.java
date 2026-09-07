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

import java.time.Duration;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link IntermediateCatchingEvent}, covering it both as a free-standing node in the flow and as an
 * event attached to an activity, since where it is stored is the only thing that separates the two.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class IntermediateCatchingEventTest
{
    private static final String VERSION_PATH = "/Workflows/timeOff/1.0";

    private static final String ACTIVITY_PATH = VERSION_PATH + "/task_1";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.create().resource(VERSION_PATH, TYPE, WorkflowVersion.RESOURCE_TYPE, "version", "1.0");
        this.context.create().resource(ACTIVITY_PATH, Map.of(
            TYPE, Activity.RESOURCE_TYPE, "elementId", "task_1", "label", "Approve the request"));
    }

    @Test
    void cancelsTheActivityWhenInterrupting()
    {
        // catching is autocreated to true in the real CND; sling-mock knows no node types, so it is set here
        final Resource resource = this.context.create().resource(ACTIVITY_PATH + "/timeout", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "timeout", "interrupting", true,
            "catching", true));
        final IntermediateCatchingEvent event = (IntermediateCatchingEvent) resource.adaptTo(FlowNode.class);

        assertNotNull(event);
        assertTrue(event.isInterrupting());
        assertTrue(event.isCatching());
    }

    @Test
    void letsTheActivityRunOnWhenNotInterrupting()
    {
        // "Escalate after five days but keep waiting" — the same shape as a deadline, a different process
        final Resource resource = this.context.create().resource(ACTIVITY_PATH + "/reminder", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "reminder", "interrupting", false));

        assertFalse(((IntermediateCatchingEvent) resource.adaptTo(FlowNode.class)).isInterrupting());
    }

    @Test
    void interruptsByDefaultWhenTheAttributeWasNeverWritten()
    {
        // BPMN's cancelActivity defaults to true, and nothing in the vocabulary maps that attribute yet, so every
        // attached event created today relies on this default. Read as false, a deadline would silently stop
        // cancelling the work it exists to abort.
        final Resource resource = this.context.create().resource(ACTIVITY_PATH + "/timeout", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "timeout"));

        assertTrue(((IntermediateCatchingEvent) resource.adaptTo(FlowNode.class)).isInterrupting());
    }

    @Test
    void findsTheActivityItWatches()
    {
        final Resource resource = this.context.create().resource(ACTIVITY_PATH + "/timeout", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "timeout"));

        final Activity watched = ((IntermediateCatchingEvent) resource.adaptTo(FlowNode.class)).getActivity();

        assertNotNull(watched);
        assertEquals("task_1", watched.getElementId());
    }

    @Test
    void watchesNothingWhenStandingInTheFlowOnItsOwn()
    {
        // Stored under the version rather than inside an activity: an ordinary mid-process catch
        final Resource resource = this.context.create().resource(VERSION_PATH + "/wait_1", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "wait_1"));

        assertNull(((IntermediateCatchingEvent) resource.adaptTo(FlowNode.class)).getActivity());
    }

    @Test
    void adaptsThroughEveryAbstractBaseInTheChain()
    {
        // Three levels of abstract base, so this is a strong check that the /libs supertype chain holds
        final Resource resource = this.context.create().resource(ACTIVITY_PATH + "/timeout", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "timeout"));

        assertEquals(IntermediateCatchingEvent.class, resource.adaptTo(FlowNode.class).getClass());
        assertEquals(IntermediateCatchingEvent.class, resource.adaptTo(Event.class).getClass());
        assertEquals(IntermediateCatchingEvent.class, resource.adaptTo(IntermediateEvent.class).getClass());
        assertEquals(IntermediateCatchingEvent.class,
            resource.adaptTo(IntermediateCatchingEvent.class).getClass());
    }

    @Test
    void exposesTheDurationOfATimer()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH + "/task_1/overdue", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "overdue", "timerDuration", "P5D"));

        assertEquals(Duration.ofDays(5),
            ((IntermediateCatchingEvent) resource.adaptTo(FlowNode.class)).getTimerDuration());
    }

    @Test
    void hasNoDurationWhenItIsNotATimer()
    {
        // An event with no duration waits for something to be delivered to it rather than for the clock
        final Resource resource = this.context.create().resource(VERSION_PATH + "/task_1/message", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "message"));

        assertNull(((IntermediateCatchingEvent) resource.adaptTo(FlowNode.class)).getTimerDuration());
    }

    @Test
    void reportsADurationNobodyCanReadAsNone()
    {
        // Guessing at what "five days" might have meant would arm the wrong deadline, and a deadline nobody can
        // read is not a deadline
        final Resource resource = this.context.create().resource(VERSION_PATH + "/task_1/vague", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "vague", "timerDuration", "five days"));

        assertNull(((IntermediateCatchingEvent) resource.adaptTo(FlowNode.class)).getTimerDuration());
    }
}
