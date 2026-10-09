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
import java.time.Period;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.GregorianCalendar;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Default;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * A Sling Model wrapping a {@code wf:IntermediateCatchingEvent} node: a point mid-process where the workflow waits.
 * A token resting here is what makes the matching incoming event acceptable, so these are the places an outside
 * event can enter a running instance. Nested under an {@link Activity} instead of directly under the version, the
 * same node type is a boundary event, waiting only for as long as that activity is running.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = {FlowNode.class, Event.class, IntermediateEvent.class},
    resourceType = IntermediateCatchingEvent.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class IntermediateCatchingEvent extends IntermediateEvent
{
    /** The {@code sling:resourceType} of a {@code wf:IntermediateCatchingEvent} node. */
    public static final String RESOURCE_TYPE = "wf/IntermediateCatchingEvent";

    private static final Logger LOGGER = LoggerFactory.getLogger(IntermediateCatchingEvent.class);

    // The node type autocreates this as true, so it is normally present. The annotation is what covers the node
    // that got past that -- one of another type carrying this resource type, or one written before the property was
    // autocreated -- which would otherwise read as false here: the exact inversion of the BPMN cancelActivity
    // default, and a silent one.
    @ValueMapValue
    @Default(booleanValues = true)
    private boolean interrupting;

    @ValueMapValue
    private String timerDuration;

    /** Whether the timer duration has been read into the two parts below. */
    private boolean durationRead;

    /** The calendar part of the timer duration, or {@code null} when this event is not a usable timer. */
    private Period datePart;

    /** The clock part of the timer duration. */
    private Duration timePart;

    /**
     * Reads the timer duration, once, as an ISO-8601 duration. Years, months and weeks are calendar units, which
     * {@link Duration} cannot hold, so the part before {@code T} is read as a {@link Period} and the rest as a
     * {@link Duration}. A negative or unreadable value is recorded and leaves this event without a timer.
     */
    private void readTimerDuration()
    {
        if (this.durationRead || this.timerDuration == null) {
            return;
        }
        this.durationRead = true;
        final int clock = this.timerDuration.indexOf('T');
        try {
            final Period date = clock == 1 ? Period.ZERO
                : Period.parse(clock < 0 ? this.timerDuration : this.timerDuration.substring(0, clock));
            final Duration time = clock < 0 ? Duration.ZERO
                : Duration.parse("PT" + this.timerDuration.substring(clock + 1));
            if (!date.isNegative() && !time.isNegative()) {
                this.datePart = date;
                this.timePart = time;
                return;
            }
        } catch (final DateTimeParseException e) {
            // Reported below, with the negative durations
        }
        LOGGER.warn("The event {} declares {} as its timer duration, which is not a positive ISO-8601 duration",
            this.getPath(), this.timerDuration);
        ErrorLogger.logProblem("timer duration is not a positive ISO-8601 duration",
            ErrorContext.of(IntermediateCatchingEvent.class, "readTimerDuration").about(this.getPath())
                .with("timerDuration", this.timerDuration));
    }

    /**
     * Whether firing this event cancels the activity being watched, or merely starts a parallel branch and lets the
     * work carry on. "Escalate after five days but keep waiting" and "give up after five days" are different
     * processes, and this is the only thing that tells them apart. Only meaningful when this event is attached to an
     * activity, which {@link #getActivity()} reports.
     *
     * @return {@code true} if firing cancels the watched activity
     */
    public boolean isInterrupting()
    {
        return this.interrupting;
    }

    /**
     * When this event fires, for a wait that started at a given moment. An event with a duration is a timer, fired
     * by the clock. An event without one waits for something to be delivered to it.
     *
     * <p>The duration is relative because a definition is shared by every instance that runs it. When the waiting
     * started is a fact about the run, and the task the event watches records it. Calendar units are counted in
     * the time zone of {@code started}: a day keeps the time of day across a change of clocks, and a month is a
     * calendar month.</p>
     *
     * @param started when the wait began
     * @return when the timer fires, or {@code null} if this event is not a timer or its duration cannot be read
     */
    @Nullable
    public Calendar getDeadline(@NotNull final Calendar started)
    {
        readTimerDuration();
        if (this.datePart == null) {
            return null;
        }
        final ZonedDateTime start = ZonedDateTime.ofInstant(started.toInstant(), started.getTimeZone().toZoneId());
        return GregorianCalendar.from(start.plus(this.datePart).plus(this.timePart));
    }

    /**
     * The activity this event is attached to, which is simply its parent. An event stored directly under the version
     * stands in the flow on its own and watches nothing, so this is how to tell a boundary event from a free-standing
     * one.
     *
     * @return the watched activity, or {@code null} if this event does not sit inside one
     */
    @Nullable
    public Activity getActivity()
    {
        return this.getParent(Activity.RESOURCE_TYPE, Activity.class);
    }
}
