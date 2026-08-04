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
package io.uhndata.iap.slacknotifications.internal;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.slacknotifications.spi.SlackNotificationProducer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledSlackNotification}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ScheduledSlackNotificationTest
{
    private final Scheduler scheduler = mock(Scheduler.class);

    private final ScheduleOptions options = mock(ScheduleOptions.class);

    private ScheduledSlackNotification component;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        when(this.options.name(anyString())).thenReturn(this.options);
        when(this.options.canRunConcurrently(true)).thenReturn(this.options);
        this.component = new ScheduledSlackNotification();
        inject("scheduler", this.scheduler);
    }

    @Test
    void aPlainValueIsUsedAsItIs()
    {
        assertEquals("0 0 9 ? * MON *", ScheduledSlackNotification.resolve("0 0 9 ? * MON *"));
        assertNull(ScheduledSlackNotification.resolve(null));
    }

    @Test
    void aValueNamingAnEnvironmentVariableIsReadFromIt()
    {
        // A webhook address is a secret, so it is named rather than written into the configuration
        assertEquals(System.getenv("PATH"), ScheduledSlackNotification.resolve("%ENV%PATH"));
    }

    @Test
    void anUnsetEnvironmentVariableResolvesToNothing()
    {
        assertNull(ScheduledSlackNotification.resolve("%ENV%IAP_NO_SUCH_VARIABLE_EXISTS"));
    }

    @Test
    void aMissingScheduleFallsBackToNightly()
    {
        assertEquals(ScheduledSlackNotification.DEFAULT_SCHEDULE,
            ScheduledSlackNotification.schedule("%ENV%IAP_NO_SUCH_VARIABLE_EXISTS"));
        assertEquals(ScheduledSlackNotification.DEFAULT_SCHEDULE, ScheduledSlackNotification.schedule("  "));
        assertEquals("0 0 0 1 * ? *", ScheduledSlackNotification.schedule("0 0 0 1 * ? *"));
    }

    @Test
    void parametersAreReadAsKeyValuePairs()
    {
        final Map<String, String> parameters = ScheduledSlackNotification.asParameters(
            new String[] { "statusReport.unprivileged=true", " spaced =  value with = signs " });

        assertEquals("true", parameters.get("statusReport.unprivileged"));
        assertEquals("  value with = signs ", parameters.get("spaced"));
    }

    @Test
    void malformedParametersAreIgnoredRatherThanRefusingTheConfiguration()
    {
        final Map<String, String> parameters =
            ScheduledSlackNotification.asParameters(new String[] { "nonsense", "good=value" });

        assertEquals(Map.of("good", "value"), parameters);
        assertTrue(ScheduledSlackNotification.asParameters(null).isEmpty());
    }

    @Test
    void schedulesTheConfiguredNotification()
    {
        when(this.scheduler.EXPR("0 0 0 1 * ? *")).thenReturn(this.options);

        this.component.activate(configuration("https://example.invalid/hook", "0 0 0 1 * ? *"));

        verify(this.options).name(ScheduledSlackNotification.JOB_PREFIX + "nightly");
        verify(this.scheduler).schedule(any(SlackNotificationsTask.class), any(ScheduleOptions.class));
    }

    @Test
    void aNotificationWithNoEndpointIsNotScheduled()
    {
        this.component.activate(configuration("%ENV%IAP_NO_SUCH_VARIABLE_EXISTS", "0 0 0 1 * ? *"));

        // Scheduling a job that could only ever fail would report the same failure every single night
        verify(this.scheduler, never()).schedule(any(), any());
    }

    @Test
    void anUnusableScheduleDoesNotStopTheModuleFromStarting()
    {
        when(this.scheduler.EXPR("nonsense")).thenThrow(new IllegalArgumentException("not a schedule"));

        this.component.activate(configuration("https://example.invalid/hook", "nonsense"));

        verify(this.scheduler, never()).schedule(any(), any());
    }

    @Test
    void removingAConfigurationUnschedulesItsJob()
    {
        this.component.deactivate(configuration("https://example.invalid/hook", "0 0 0 1 * ? *"));

        verify(this.scheduler).unschedule(ScheduledSlackNotification.JOB_PREFIX + "nightly");
    }

    @Test
    void producersRegisteringDuringARunDoNotDisturbIt() throws ReflectiveOperationException
    {
        // Declarative Services updates this list in place, on whichever thread starts or stops a producer bundle,
        // while a scheduled notification may be iterating it. A plain list answers that with a
        // ConcurrentModificationException, taking the notification down with it.
        final List<SlackNotificationProducer> producers = producers();
        producers.add(mock(SlackNotificationProducer.class));

        assertDoesNotThrow(() -> {
            final Iterator<SlackNotificationProducer> run = producers.iterator();
            while (run.hasNext()) {
                run.next();
                producers.add(mock(SlackNotificationProducer.class));
            }
        });
    }

    @Test
    void aRunSeesTheProducersRegisteredSoFarAndNotThoseAddedLater() throws ReflectiveOperationException
    {
        final List<SlackNotificationProducer> producers = producers();
        producers.add(mock(SlackNotificationProducer.class));
        final Iterator<SlackNotificationProducer> run = producers.iterator();

        producers.add(mock(SlackNotificationProducer.class));

        // The run works from the set that existed when it started; the newcomer is picked up by the next one
        assertTrue(run.hasNext());
        run.next();
        assertFalse(run.hasNext());
        assertEquals(2, producers.size());
    }

    @SuppressWarnings("unchecked")
    private List<SlackNotificationProducer> producers() throws ReflectiveOperationException
    {
        final Field field = ScheduledSlackNotification.class.getDeclaredField("producers");
        field.setAccessible(true);
        return (List<SlackNotificationProducer>) field.get(this.component);
    }

    private void inject(final String name, final Object value) throws ReflectiveOperationException
    {
        final Field field = ScheduledSlackNotification.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.component, value);
    }

    private SlackNotificationConfiguration configuration(final String endpoint, final String schedule)
    {
        final SlackNotificationConfiguration config = mock(SlackNotificationConfiguration.class);
        when(config.name()).thenReturn("nightly");
        when(config.endpoint()).thenReturn(endpoint);
        when(config.schedule()).thenReturn(schedule);
        when(config.title()).thenReturn("Nightly");
        when(config.include()).thenReturn(new String[0]);
        when(config.notificationParameters()).thenReturn(new String[0]);
        when(config.skipEmpty()).thenReturn(true);
        return config;
    }
}
