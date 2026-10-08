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

import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;

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

    private static final String UTC = "UTC";

    private static final String TORONTO = "America/Toronto";

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
    void countsADeadlineFromWhenTheWaitStarted()
    {
        final Calendar start = at(2026, Calendar.MARCH, 1, 12, UTC);

        assertFires(at(2026, Calendar.MARCH, 6, 12, UTC), "P5D", start);
        assertFires(at(2026, Calendar.MARCH, 2, 12, UTC), "PT24H", start);
        assertFires(at(2026, Calendar.MARCH, 3, 0, UTC), "P1DT12H", start);
    }

    @Test
    void readsWeeksMonthsAndYearsAsCalendarUnits()
    {
        assertFires(at(2026, Calendar.MARCH, 15, 12, UTC), "P2W", at(2026, Calendar.MARCH, 1, 12, UTC));
        // A month from the last day of January is the last day of February
        assertFires(at(2026, Calendar.FEBRUARY, 28, 12, UTC), "P1M", at(2026, Calendar.JANUARY, 31, 12, UTC));
        assertFires(at(2027, Calendar.MARCH, 1, 12, UTC), "P1Y", at(2026, Calendar.MARCH, 1, 12, UTC));
    }

    @Test
    void keepsTheTimeOfDayAcrossAChangeOfClocks()
    {
        // Clocks go back on 1 November 2026 in Toronto, so five days later is 121 hours later
        final Calendar due = deadline("P5D", at(2026, Calendar.OCTOBER, 30, 12, TORONTO));

        assertEquals(at(2026, Calendar.NOVEMBER, 4, 12, TORONTO).getTimeInMillis(), due.getTimeInMillis());
    }

    @Test
    void placesAVeryDistantDeadlineInTheFuture()
    {
        final Calendar start = at(2026, Calendar.MARCH, 1, 12, UTC);

        assertTrue(deadline("P30000D", start).after(start));
    }

    @Test
    void hasNoDeadlineWhenItIsNotATimer()
    {
        final Resource resource = this.context.create().resource(VERSION_PATH + "/task_1/message", Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", "message"));

        assertNull(((IntermediateCatchingEvent) resource.adaptTo(FlowNode.class))
            .getDeadline(at(2026, Calendar.MARCH, 1, 12, UTC)));
    }

    @Test
    void recordsADurationNobodyCanReadOnce()
    {
        final ErrorLoggerService errors = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(errors);
        try {
            for (final String unreadable : new String[] {"five days", "-P5D", "P", "PT", "T5H"}) {
                assertNull(deadline(unreadable, at(2026, Calendar.MARCH, 1, 12, UTC)), unreadable);
            }
            final IntermediateCatchingEvent event = timer("vague", "five days");
            event.getDeadline(at(2026, Calendar.MARCH, 1, 12, UTC));
            event.getDeadline(at(2026, Calendar.MARCH, 1, 12, UTC));
        } finally {
            ErrorLogger.unsetService(errors);
        }

        Mockito.verify(errors, Mockito.times(6)).logProblem(Mockito.anyString(), Mockito.any());
    }

    private void assertFires(final Calendar expected, final String duration, final Calendar start)
    {
        assertEquals(expected.toInstant(), deadline(duration, start).toInstant(), duration);
    }

    /**
     * When a timer with the given duration fires, for a wait that started at a given moment.
     *
     * @param duration the timer duration
     * @param start when the wait started
     * @return the deadline, or {@code null}
     */
    private Calendar deadline(final String duration, final Calendar start)
    {
        return timer("t" + Math.abs(duration.hashCode()), duration).getDeadline(start);
    }

    private IntermediateCatchingEvent timer(final String name, final String duration)
    {
        final Resource resource = this.context.create().resource(VERSION_PATH + "/task_1/" + name, Map.of(
            TYPE, IntermediateCatchingEvent.RESOURCE_TYPE, "elementId", name, "timerDuration", duration));
        return (IntermediateCatchingEvent) resource.adaptTo(FlowNode.class);
    }

    private static Calendar at(final int year, final int month, final int day, final int hour, final String zone)
    {
        final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(zone));
        calendar.clear();
        calendar.set(year, month, day, hour, 0, 0);
        return calendar;
    }
}
