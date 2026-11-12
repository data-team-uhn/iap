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
package io.uhndata.iap.metrics.internal;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.osgi.framework.Constants;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.metrics.api.MetricsException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MetricRolloverScheduler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MetricRolloverSchedulerTest
{
    private static final String NIGHTLY = "0 0 0 * * ?";

    private static final String NOON = "0 0 12 * * ?";

    private final SlingContext context = new SlingContext();

    private final Scheduler scheduler = Mockito.mock(Scheduler.class);

    private final ScheduleOptions options = Mockito.mock(ScheduleOptions.class);

    private ResourceResolverFactory factory;

    private MetricsManagerImpl manager;

    @BeforeEach
    void setUp() throws Exception
    {
        this.factory = this.context.getService(ResourceResolverFactory.class);
        this.manager = this.context.registerInjectActivateService(MetricsManagerImpl.class);
        this.context.registerService(Scheduler.class, this.scheduler);
        Mockito.when(this.scheduler.EXPR(Mockito.anyString())).thenReturn(this.options);
        Mockito.when(this.scheduler.schedule(Mockito.any(), Mockito.any())).thenReturn(true);
        try (ResourceResolver resolver = open()) {
            resolver.create(resolver.getResource("/"), "Metrics",
                Map.of("sling:resourceType", "metric/Homepage"));
            resolver.commit();
        }
    }

    @Test
    void schedulesEveryMetricWithAScheduleOnActivation()
    {
        this.manager.createMetric("nightly").withRolloverSchedule(NIGHTLY).create();
        this.manager.createMetric("manual").create();
        this.manager.createMetric("noon").withRolloverSchedule(NOON).create();

        startScheduler();

        Mockito.verify(this.scheduler).EXPR(NIGHTLY);
        Mockito.verify(this.scheduler).EXPR(NOON);
        Mockito.verify(this.scheduler, Mockito.times(2)).schedule(Mockito.any(), Mockito.eq(this.options));
        Mockito.verify(this.options).name("iap-metrics-rollover-nightly");
        Mockito.verify(this.options).name("iap-metrics-rollover-noon");
        Mockito.verify(this.options, Mockito.times(2)).canRunConcurrently(false);
        Mockito.verify(this.options, Mockito.times(2)).onLeaderOnly(true);
        Mockito.verify(this.scheduler, Mockito.never()).unschedule(Mockito.anyString());
    }

    @Test
    void blankSchedulesAreIgnored() throws Exception
    {
        try (ResourceResolver resolver = open()) {
            resolver.create(resolver.getResource("/Metrics"), "blank",
                Map.of("jcr:primaryType", "metric:Metric", "label", "Blank", "rolloverSchedule", "   "));
            resolver.commit();
        }

        startScheduler();

        Mockito.verify(this.scheduler, Mockito.never()).schedule(Mockito.any(), Mockito.any());
    }

    @Test
    void nothingToScheduleWithoutTheHomepage() throws Exception
    {
        try (ResourceResolver resolver = open()) {
            resolver.delete(resolver.getResource("/Metrics"));
            resolver.commit();
        }

        startScheduler();

        Mockito.verify(this.scheduler, Mockito.never()).schedule(Mockito.any(), Mockito.any());
    }

    @Test
    void newMetricsAreScheduledOnChangeEvents()
    {
        this.manager.createMetric("nightly").withRolloverSchedule(NIGHTLY).create();
        final MetricRolloverScheduler rolloverScheduler = startScheduler();

        this.manager.createMetric("noon").withRolloverSchedule(NOON).create();
        rolloverScheduler.onChange(List.of());

        Mockito.verify(this.scheduler, Mockito.times(2)).schedule(Mockito.any(), Mockito.eq(this.options));
        Mockito.verify(this.scheduler).EXPR(NOON);
        Mockito.verify(this.scheduler, Mockito.never()).unschedule(Mockito.anyString());
    }

    @Test
    void irrelevantChangesDoNotDisturbTheSchedules()
    {
        this.manager.createMetric("nightly").withRolloverSchedule(NIGHTLY).create();
        final MetricRolloverScheduler rolloverScheduler = startScheduler();

        this.manager.getMetric("nightly").orElseThrow().increment();
        rolloverScheduler.onChange(List.of());

        Mockito.verify(this.scheduler, Mockito.times(1)).schedule(Mockito.any(), Mockito.any());
        Mockito.verify(this.scheduler, Mockito.never()).unschedule(Mockito.anyString());
    }

    @Test
    void changedSchedulesAreRescheduled()
    {
        this.manager.createMetric("periodic").withRolloverSchedule(NIGHTLY).create();
        final MetricRolloverScheduler rolloverScheduler = startScheduler();

        this.manager.createMetric("periodic").withRolloverSchedule(NOON).create();
        rolloverScheduler.onChange(List.of());

        Mockito.verify(this.scheduler).unschedule("iap-metrics-rollover-periodic");
        Mockito.verify(this.scheduler).EXPR(NIGHTLY);
        Mockito.verify(this.scheduler).EXPR(NOON);
        Mockito.verify(this.scheduler, Mockito.times(2)).schedule(Mockito.any(), Mockito.any());
    }

    @Test
    void removedSchedulesAreUnscheduled()
    {
        this.manager.createMetric("periodic").withRolloverSchedule(NIGHTLY).create();
        final MetricRolloverScheduler rolloverScheduler = startScheduler();

        this.manager.createMetric("periodic").create();
        rolloverScheduler.onChange(List.of());

        Mockito.verify(this.scheduler).unschedule("iap-metrics-rollover-periodic");
        Mockito.verify(this.scheduler, Mockito.times(1)).schedule(Mockito.any(), Mockito.any());
    }

    @Test
    void deletedMetricsAreUnscheduled() throws Exception
    {
        this.manager.createMetric("periodic").withRolloverSchedule(NIGHTLY).create();
        final MetricRolloverScheduler rolloverScheduler = startScheduler();

        try (ResourceResolver resolver = open()) {
            resolver.delete(resolver.getResource("/Metrics/periodic"));
            resolver.commit();
        }
        rolloverScheduler.onChange(List.of());

        Mockito.verify(this.scheduler).unschedule("iap-metrics-rollover-periodic");
        Mockito.verify(this.scheduler, Mockito.times(1)).schedule(Mockito.any(), Mockito.any());
    }

    @Test
    void deactivationUnschedulesEverything()
    {
        this.manager.createMetric("nightly").withRolloverSchedule(NIGHTLY).create();
        this.manager.createMetric("noon").withRolloverSchedule(NOON).create();
        final MetricRolloverScheduler rolloverScheduler = startScheduler();

        rolloverScheduler.deactivate();

        Mockito.verify(this.scheduler).unschedule("iap-metrics-rollover-nightly");
        Mockito.verify(this.scheduler).unschedule("iap-metrics-rollover-noon");
    }

    @Test
    void aRefusedScheduleIsNotRetriedAsTheMetricIsIncremented()
    {
        Mockito.when(this.scheduler.schedule(Mockito.any(), Mockito.any())).thenReturn(false);
        this.manager.createMetric("nightly").withRolloverSchedule(NIGHTLY).create();
        final MetricRolloverScheduler rolloverScheduler = startScheduler();

        for (int increment = 0; increment < 3; ++increment) {
            this.manager.getMetric("nightly").orElseThrow().increment();
            rolloverScheduler.onChange(List.of());
        }

        Mockito.verify(this.scheduler, Mockito.times(1)).schedule(Mockito.any(), Mockito.any());
    }

    @Test
    void aRefusedScheduleIsRetriedOnceTheScheduleChanges()
    {
        Mockito.when(this.scheduler.schedule(Mockito.any(), Mockito.any())).thenReturn(false, true);
        this.manager.createMetric("periodic").withRolloverSchedule(NIGHTLY).create();
        final MetricRolloverScheduler rolloverScheduler = startScheduler();

        this.manager.createMetric("periodic").withRolloverSchedule(NOON).create();
        rolloverScheduler.onChange(List.of());
        rolloverScheduler.onChange(List.of());

        Mockito.verify(this.scheduler).EXPR(NIGHTLY);
        Mockito.verify(this.scheduler).EXPR(NOON);
        Mockito.verify(this.scheduler, Mockito.times(2)).schedule(Mockito.any(), Mockito.any());
        Mockito.verify(this.scheduler, Mockito.never()).unschedule(Mockito.anyString());
    }

    @Test
    void aRefusedScheduleIsRetriedWhenTheMetricIsRecreatedWithIt() throws Exception
    {
        Mockito.when(this.scheduler.schedule(Mockito.any(), Mockito.any())).thenReturn(false, true);
        this.manager.createMetric("periodic").withRolloverSchedule(NIGHTLY).create();
        final MetricRolloverScheduler rolloverScheduler = startScheduler();

        try (ResourceResolver resolver = open()) {
            resolver.delete(resolver.getResource("/Metrics/periodic"));
            resolver.commit();
        }
        rolloverScheduler.onChange(List.of());
        this.manager.createMetric("periodic").withRolloverSchedule(NIGHTLY).create();
        rolloverScheduler.onChange(List.of());

        Mockito.verify(this.scheduler, Mockito.times(2)).schedule(Mockito.any(), Mockito.any());
    }

    @Test
    void schedulingFailuresAreNotPropagated()
    {
        Mockito.when(this.scheduler.schedule(Mockito.any(), Mockito.any()))
            .thenThrow(new IllegalArgumentException("Expression cannot be parsed"));
        this.manager.createMetric("broken").withRolloverSchedule("not a cron expression").create();

        assertDoesNotThrow(this::startScheduler);
    }

    @Test
    void unreadableRepositoriesAreNotFatal() throws Exception
    {
        this.manager.createMetric("nightly").withRolloverSchedule(NIGHTLY).create();
        final ResourceResolverFactory broken = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(broken.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no service user"));
        outrankTheContextFactory(broken);

        assertDoesNotThrow(this::startScheduler);
        Mockito.verifyNoInteractions(this.scheduler);
    }

    @Test
    void unreadableSchedulesAreRecorded() throws Exception
    {
        final ResourceResolverFactory broken = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(broken.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no service user"));
        outrankTheContextFactory(broken);
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);

        try {
            startScheduler();

            final ArgumentCaptor<ErrorContext> recorded = ArgumentCaptor.forClass(ErrorContext.class);
            Mockito.verify(recorder).logError(Mockito.any(MetricsException.class), recorded.capture());
            assertEquals("readSchedules", recorded.getValue().getOperation());
        } finally {
            ErrorLogger.unsetService(recorder);
        }
    }

    @Test
    void theJobRollsTheMetricOver() throws Exception
    {
        this.manager.createMetric("counted").withRolloverSchedule(NIGHTLY).create();
        try (ResourceResolver resolver = open()) {
            resolver.getResource("/Metrics/counted").adaptTo(
                org.apache.sling.api.resource.ModifiableValueMap.class).put("oak:counter", 7L);
            resolver.commit();
        }
        final ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        startScheduler();
        Mockito.verify(this.scheduler).schedule(job.capture(), Mockito.any());

        job.getValue().run();

        assertEquals(7L, this.manager.getMetric("counted").orElseThrow().getPreviousValue());
        assertEquals(7L, this.manager.getMetric("counted").orElseThrow().getLastDelta());
        assertTrue(this.manager.getMetric("counted").orElseThrow().getLastRollover() != null);
    }

    @Test
    void theJobSurvivesADeletedMetric() throws Exception
    {
        this.manager.createMetric("counted").withRolloverSchedule(NIGHTLY).create();
        final ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        startScheduler();
        Mockito.verify(this.scheduler).schedule(job.capture(), Mockito.any());

        try (ResourceResolver resolver = open()) {
            resolver.delete(resolver.getResource("/Metrics/counted"));
            resolver.commit();
        }

        assertDoesNotThrow(() -> job.getValue().run());
    }

    @Test
    void theJobSurvivesRollOverFailures() throws Exception
    {
        final ResourceResolverFactory failing =
            Mockito.mock(ResourceResolverFactory.class, AdditionalAnswers.delegatesTo(this.factory));
        outrankTheContextFactory(failing);
        this.manager.createMetric("counted").withRolloverSchedule(NIGHTLY).create();
        final ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        startScheduler();
        Mockito.verify(this.scheduler).schedule(job.capture(), Mockito.any());

        // The service user can no longer log in once the job has started
        Mockito.doThrow(new LoginException("repository unavailable"))
            .when(failing).getServiceResourceResolver(Mockito.anyMap());

        assertDoesNotThrow(() -> job.getValue().run());
        assertNull(this.manager.getMetric("counted").orElseThrow().getLastRollover());
    }

    private ResourceResolver open() throws Exception
    {
        return this.factory.getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, "metrics"));
    }

    private MetricRolloverScheduler startScheduler()
    {
        return this.context.registerInjectActivateService(MetricRolloverScheduler.class);
    }

    /** Have the scheduler started next reach the repository through the given factory instead of the context's. */
    private void outrankTheContextFactory(final ResourceResolverFactory resolverFactory)
    {
        this.context.registerService(ResourceResolverFactory.class, resolverFactory,
            Constants.SERVICE_RANKING, Integer.MAX_VALUE);
    }
}
