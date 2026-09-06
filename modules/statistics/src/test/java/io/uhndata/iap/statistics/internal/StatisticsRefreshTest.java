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
package io.uhndata.iap.statistics.internal;

import java.lang.reflect.Field;

import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StatisticsRefresh}: when the metrics are worked out again, and what happens when
 * the schedule cannot be followed.
 *
 * @version $Id$
 * @since 0.1.0
 */
class StatisticsRefreshTest
{
    private static final String HOURLY = "0 0 * * * ? *";

    private final StatisticsRefresh refresher = new StatisticsRefresh();

    private final Scheduler scheduler = Mockito.mock(Scheduler.class);

    private final MetricStore store = Mockito.mock(MetricStore.class);

    private final ScheduleOptions options = Mockito.mock(ScheduleOptions.class);

    @BeforeEach
    void setUp() throws Exception
    {
        Mockito.when(this.scheduler.EXPR(Mockito.anyString())).thenReturn(this.options);
        Mockito.when(this.scheduler.NOW(Mockito.anyInt(), Mockito.anyLong())).thenReturn(this.options);
        Mockito.when(this.scheduler.schedule(Mockito.any(), Mockito.any())).thenReturn(true);
        inject("scheduler", this.scheduler);
        inject("store", this.store);
    }

    // One instance of a cluster reads the history, and every instance shows what it found
    @Test
    void worksTheMetricsOutOnTheConfiguredScheduleAndOnTheLeaderAlone()
    {
        this.refresher.activate(configured(HOURLY));

        Mockito.verify(this.scheduler).EXPR(HOURLY);
        Mockito.verify(this.options).name(StatisticsRefresh.JOB_NAME);
        Mockito.verify(this.options).onLeaderOnly(true);
        Mockito.verify(this.options).canRunConcurrently(false);
    }

    // A schedule nobody can parse must not mean "never refresh": the figures would then quietly stay at
    // whatever they were on the day the configuration was edited
    @Test
    void fallsBackToTheDefaultWhenTheConfiguredScheduleIsRefused()
    {
        Mockito.when(this.scheduler.schedule(Mockito.any(), Mockito.any())).thenReturn(false, true);

        this.refresher.activate(configured("nonsense"));

        Mockito.verify(this.scheduler).EXPR("nonsense");
        Mockito.verify(this.scheduler).EXPR(StatisticsRefresh.DEFAULT_SCHEDULE);
    }

    @Test
    void fallsBackToTheDefaultWhenTheConfiguredScheduleIsRejectedOutright()
    {
        Mockito.when(this.scheduler.EXPR("nonsense")).thenThrow(new IllegalArgumentException("no"));

        this.refresher.activate(configured("nonsense"));

        Mockito.verify(this.scheduler).EXPR(StatisticsRefresh.DEFAULT_SCHEDULE);
    }

    // Nothing to fall back to: trying the same expression twice would only report the same failure twice
    @Test
    void doesNotRetryADefaultScheduleThatWasRefused()
    {
        Mockito.when(this.scheduler.schedule(Mockito.any(), Mockito.any())).thenReturn(false);

        this.refresher.activate(configured(StatisticsRefresh.DEFAULT_SCHEDULE));

        Mockito.verify(this.scheduler, Mockito.times(1)).EXPR(StatisticsRefresh.DEFAULT_SCHEDULE);
    }

    // A fresh installation should not be blank until the small hours - and it tries more than once,
    // because the content it reads is installed on another thread
    @Test
    void worksThemOutShortlyAfterStartingWhenThereAreNoFiguresYet()
    {
        Mockito.when(this.store.isEmpty()).thenReturn(true);

        this.refresher.activate(configured(HOURLY));

        Mockito.verify(this.scheduler)
            .NOW(StatisticsRefresh.INITIAL_TRIES, StatisticsRefresh.INITIAL_PERIOD_SECONDS);
        Mockito.verify(this.options).name(StatisticsRefresh.INITIAL_JOB_NAME);
        assertTrue(StatisticsRefresh.INITIAL_TRIES > 1);
    }

    // A restart has figures already, and can wait for the next scheduled run
    @Test
    void leavesAnInstanceThatAlreadyHasFiguresAlone()
    {
        Mockito.when(this.store.isEmpty()).thenReturn(false);

        this.refresher.activate(configured(HOURLY));

        Mockito.verify(this.scheduler, Mockito.never()).NOW(Mockito.anyInt(), Mockito.anyLong());
    }

    // Not fatal: the recurring job will do it, and an administrator can ask for it sooner
    @Test
    void survivesTheFirstPassNotBeingScheduled()
    {
        Mockito.when(this.store.isEmpty()).thenReturn(true);
        Mockito.when(this.scheduler.NOW(Mockito.anyInt(), Mockito.anyLong()))
            .thenThrow(new IllegalStateException("no scheduler"));

        this.refresher.activate(configured(HOURLY));

        Mockito.verify(this.scheduler).EXPR(HOURLY);
    }

    @Test
    void stopsRefreshingWhenItGoesAway()
    {
        this.refresher.deactivate();

        Mockito.verify(this.scheduler).unschedule(StatisticsRefresh.JOB_NAME);
        Mockito.verify(this.scheduler).unschedule(StatisticsRefresh.INITIAL_JOB_NAME);
    }

    @Test
    void worksTheMetricsOutWhenItFires()
    {
        this.refresher.refresh();

        Mockito.verify(this.store).refresh();
    }

    // One bad night leaves the job scheduled and the previous figures in place
    @Test
    void survivesARefreshThatFails()
    {
        Mockito.when(this.store.refresh()).thenThrow(new IllegalStateException("the history is gone"));

        this.refresher.refresh();

        Mockito.verify(this.store).refresh();
    }

    private static StatisticsRefreshConfiguration configured(final String schedule)
    {
        final StatisticsRefreshConfiguration config =
            Mockito.mock(StatisticsRefreshConfiguration.class);
        Mockito.when(config.schedule()).thenReturn(schedule);
        return config;
    }

    private void inject(final String field, final Object value) throws ReflectiveOperationException
    {
        final Field declared = StatisticsRefresh.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(this.refresher, value);
    }
}
