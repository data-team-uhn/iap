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
package io.uhndata.iap.demo.review;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * PRETOTYPE. Keeps a submission's review deadline, as {@code reviewDue} on the submission, so the review queue can
 * sort and count down on one property. The activity's {@code action} says what happened: {@code start} sets the
 * deadline {@code duration} from now, or {@code fastTrackDuration} when the submission carries the
 * {@code fast-track} tag; {@code pause} stops the clock while the submission is back with its submitter, recording
 * {@code reviewPausedAt}; {@code resume} moves the deadline on by however long it was paused.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ReviewClockHandler implements ServiceTaskHandler
{
    /** The property holding the deadline. */
    public static final String DUE_PROPERTY = "reviewDue";

    /** The property holding when the clock was paused, while it is. */
    public static final String PAUSED_PROPERTY = "reviewPausedAt";

    private static final String FAST_TRACK_TAG = "fast-track";

    @Override
    public String getName()
    {
        return "reviewClock";
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource host = ExecutionHost.of(context);
        final ModifiableValueMap properties = Objects.requireNonNull(host.adaptTo(ModifiableValueMap.class),
            "A submission's properties can be written by the engine");
        final String action = String.valueOf(context.getActivity().get("action"));
        final ZonedDateTime now = ZonedDateTime.now();
        switch (action) {
            case "start" -> {
                final Taggable tags = Objects.requireNonNull(host.adaptTo(Taggable.class), "Anything is taggable");
                final String setting = tags.hasTag(FAST_TRACK_TAG) ? "fastTrackDuration" : "duration";
                properties.put(DUE_PROPERTY, calendar(add(now, durationOf(context, setting))));
                properties.remove(PAUSED_PROPERTY);
            }
            case "pause" -> properties.put(PAUSED_PROPERTY, calendar(now));
            case "resume" -> {
                final Calendar paused = properties.get(PAUSED_PROPERTY, Calendar.class);
                final Calendar due = properties.get(DUE_PROPERTY, Calendar.class);
                if (paused != null && due != null) {
                    final Duration away = Duration.between(paused.toInstant(), now.toInstant());
                    properties.put(DUE_PROPERTY, calendar(zoned(due.toInstant()).plus(away)));
                }
                properties.remove(PAUSED_PROPERTY);
            }
            case null, default -> throw new WorkflowDefinitionException("The activity "
                + context.getActivity().getPath() + " names no reviewClock action it knows: " + action);
        }
    }

    private static String durationOf(final WorkflowTaskContext context, final String setting)
        throws WorkflowDefinitionException
    {
        final Object value = context.getActivity().get(setting);
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " does not configure its " + setting);
        }
        return (String) value;
    }

    // An ISO-8601 duration, years and months included, as timers read theirs
    private static ZonedDateTime add(final ZonedDateTime from, final String duration)
    {
        final int time = duration.indexOf('T');
        final ZonedDateTime dated = time == 1 ? from : from.plus(Period.parse(time < 0 ? duration
            : duration.substring(0, time)));
        return time < 0 ? dated : dated.plus(Duration.parse("PT" + duration.substring(time + 1)));
    }

    private static ZonedDateTime zoned(final Instant instant)
    {
        return instant.atZone(ZoneId.systemDefault());
    }

    private static Calendar calendar(final ZonedDateTime time)
    {
        return GregorianCalendar.from(time);
    }
}
