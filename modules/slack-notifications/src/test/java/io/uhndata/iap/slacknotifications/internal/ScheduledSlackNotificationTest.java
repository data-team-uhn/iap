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

import java.io.IOException;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import io.uhndata.iap.httprequests.api.HttpRequests;
import io.uhndata.iap.httprequests.api.HttpResponse;
import io.uhndata.iap.slacknotifications.spi.SlackNotificationProducer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
@ExtendWith(SlingContextExtension.class)
class ScheduledSlackNotificationTest
{
    /** A producer whose bundle starts after the notification was scheduled. */
    private static final class LateProducer implements SlackNotificationProducer
    {
        @Override
        public String getName()
        {
            return "late";
        }

        @Override
        public List<JsonObject> prepareMessages(final Map<String, String> extraParameters)
        {
            return List.of(Json.createObjectBuilder().add(TITLE_KEY, "late arrival").build());
        }
    }

    private static final String ENDPOINT = "https://example.invalid/hook";

    private static final String MONTHLY = "0 0 0 1 * ? *";

    private static final String UNSET_VARIABLE = "%ENV%IAP_NO_SUCH_VARIABLE_EXISTS";

    private final SlingContext context = new SlingContext();

    private final Scheduler scheduler = mock(Scheduler.class);

    private final ScheduleOptions options = mock(ScheduleOptions.class);

    private final HttpRequests webhook = mock(HttpRequests.class);

    @BeforeEach
    void setUp() throws IOException
    {
        when(this.options.name(anyString())).thenReturn(this.options);
        when(this.options.canRunConcurrently(true)).thenReturn(this.options);
        when(this.scheduler.EXPR(MONTHLY)).thenReturn(this.options);
        when(this.webhook.post(anyString(), anyString(), anyString())).thenReturn(new HttpResponse(200, "ok"));
        this.context.registerService(Scheduler.class, this.scheduler);
        this.context.registerService(HttpRequests.class, this.webhook);
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
        assertEquals(System.getenv("PATH"), ScheduledSlackNotification.resolve("%ENV%PATH"));
    }

    @Test
    void anUnsetEnvironmentVariableResolvesToNothing()
    {
        assertNull(ScheduledSlackNotification.resolve(UNSET_VARIABLE));
    }

    @Test
    void aMissingScheduleFallsBackToNightly()
    {
        assertEquals(ScheduledSlackNotification.DEFAULT_SCHEDULE, ScheduledSlackNotification.schedule(UNSET_VARIABLE));
        assertEquals(ScheduledSlackNotification.DEFAULT_SCHEDULE, ScheduledSlackNotification.schedule("  "));
        assertEquals(MONTHLY, ScheduledSlackNotification.schedule(MONTHLY));
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
        this.activate(ENDPOINT, MONTHLY);

        verify(this.options).name(ScheduledSlackNotification.JOB_PREFIX + "nightly");
        verify(this.scheduler).schedule(any(SlackNotificationsTask.class), eq(this.options));
    }

    @Test
    void aNotificationWithNoEndpointIsNotScheduled()
    {
        this.activate(UNSET_VARIABLE, MONTHLY);

        verify(this.scheduler, never()).schedule(any(), any());
    }

    @Test
    void anUnusableScheduleDoesNotStopTheModuleFromStarting()
    {
        when(this.scheduler.EXPR("nonsense")).thenThrow(new IllegalArgumentException("not a schedule"));

        this.activate(ENDPOINT, "nonsense");

        verify(this.scheduler, never()).schedule(any(), any());
    }

    @Test
    void removingAConfigurationUnschedulesItsJob()
    {
        final ScheduledSlackNotification notification = this.activate(ENDPOINT, MONTHLY);
        final SlackNotificationConfiguration removed = mock(SlackNotificationConfiguration.class);
        when(removed.name()).thenReturn("nightly");

        notification.deactivate(removed);

        verify(this.scheduler).unschedule(ScheduledSlackNotification.JOB_PREFIX + "nightly");
    }

    // A producer bundle can start after the notification is scheduled, and its next run must ask it
    @Test
    void aRunAsksTheProducersRegisteredSinceTheNotificationWasScheduled() throws IOException
    {
        final Runnable job = this.scheduledJob();
        this.context.registerService(SlackNotificationProducer.class, new LateProducer());

        job.run();

        verify(this.webhook).post(eq(ENDPOINT), contains("late arrival"), eq("application/json"));
    }

    /** Activates a notification the way the component runtime would, with its references wired. */
    private ScheduledSlackNotification activate(final String endpoint, final String schedule)
    {
        return this.context.registerInjectActivateService(new ScheduledSlackNotification(),
            "name", "nightly", "endpoint", endpoint, "schedule", schedule, "title", "Nightly");
    }

    /** The job a monthly notification hands the scheduler. */
    private Runnable scheduledJob()
    {
        this.activate(ENDPOINT, MONTHLY);
        final ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        verify(this.scheduler).schedule(job.capture(), eq(this.options));
        return job.getValue();
    }
}
