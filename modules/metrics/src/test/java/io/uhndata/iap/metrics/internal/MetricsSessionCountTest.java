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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.osgi.framework.Constants;

import io.uhndata.iap.metrics.api.Metric;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How many repository sessions listing and reading the metrics open. Each session is a service login.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MetricsSessionCountTest
{
    /** How many metrics the counted listings run over; more than one, so a per-metric cost is visible. */
    private static final int METRICS = 5;

    private final SlingContext context = new SlingContext();

    private final AtomicInteger sessions = new AtomicInteger();

    private MetricsManagerImpl manager;

    private ResourceResolverFactory counting;

    private ResourceResolverFactory real;

    @BeforeEach
    void setUp() throws Exception
    {
        this.real = this.context.getService(ResourceResolverFactory.class);
        this.counting = Mockito.mock(ResourceResolverFactory.class, AdditionalAnswers.delegatesTo(this.real));
        Mockito.doAnswer(invocation -> {
            this.sessions.incrementAndGet();
            return this.real.getServiceResourceResolver(invocation.getArgument(0));
        }).when(this.counting).getServiceResourceResolver(Mockito.any());

        // Outranks the context's own factory, so every component started here counts its sessions
        this.context.registerService(ResourceResolverFactory.class, this.counting,
            Constants.SERVICE_RANKING, Integer.MAX_VALUE);
        this.manager = this.context.registerInjectActivateService(MetricsManagerImpl.class);
        try (ResourceResolver resolver =
            this.real.getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, "metrics"))) {
            resolver.create(resolver.getResource("/"), "Metrics",
                Map.of("sling:resourceType", "metric/Homepage"));
            resolver.commit();
        }
        for (int i = 0; i < METRICS; ++i) {
            this.manager.createMetric("metric" + i).withCategory("Counted").withDefaultOrder(i).create();
        }
        this.sessions.set(0);
    }

    @Test
    void listingTheMetricsOpensOneSession()
    {
        assertEquals(METRICS, this.manager.getMetrics().size());
        assertEquals(1, this.sessions.get());
    }

    @Test
    void readingEveryPropertyOfAListedMetricStaysWithinTheListingSession()
    {
        this.manager.getMetrics().forEach(metric -> {
            metric.getLabel();
            metric.getDescription();
            metric.getCategory();
            metric.getDefaultOrder();
            metric.getAccessLevel();
            metric.getCurrentValue();
            metric.getPreviousValue();
            metric.getCurrentDelta();
            metric.getLastDelta();
            metric.getLastUpdated();
            metric.getLastRollover();
            metric.getRolloverSchedule();
            metric.toJson();
        });

        assertEquals(1, this.sessions.get());
    }

    @Test
    void theStatusReportOpensOneSession()
    {
        final MetricsStatusReporter reporter = this.context.registerInjectActivateService(MetricsStatusReporter.class);

        reporter.report(false);

        assertEquals(1, this.sessions.get());
    }

    @Test
    void aSingleMetricLookupOpensOneSessionAndThenReadsLive()
    {
        final Metric metric = this.manager.getMetric("metric0").orElseThrow();
        assertEquals(1, this.sessions.get());

        metric.getLabel();
        metric.getCurrentValue();
        assertEquals(3, this.sessions.get());
    }

    @Test
    void aListedMetricStillWritesToTheRepository() throws Exception
    {
        final Metric listed = this.manager.getMetrics().get(0);

        listed.increment(3);

        // The mock repository has no atomic counter, so oak:increment stays as it was written
        assertEquals(3L, properties(listed.getName()).get("oak:increment", Long.class));
    }

    @Test
    void aListedMetricRollsOverAgainstLiveValuesRatherThanItsOwn() throws Exception
    {
        // Listed while the count is 0, which then becomes 42
        final Metric listed = this.manager.getMetrics().get(0);
        setCounter(listed.getName(), 42);

        listed.rollOver();

        assertEquals(42L, properties(listed.getName()).get("previousValue", Long.class));
        assertEquals(0L, listed.getCurrentValue(), "the listed handle keeps reporting what it was listed with");
    }

    @Test
    void aScheduledRollOverOpensOneSession()
    {
        this.context.registerService(Scheduler.class, Mockito.mock(Scheduler.class));
        final MetricRolloverScheduler scheduler =
            this.context.registerInjectActivateService(MetricRolloverScheduler.class);
        // Starting up reads the schedules, which is not what this counts
        this.sessions.set(0);

        scheduler.rollOver("metric0");

        assertEquals(1, this.sessions.get());
    }

    private ValueMap properties(final String metric) throws Exception
    {
        try (ResourceResolver resolver = this.real.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, "metrics"))) {
            return new ValueMapDecorator(
                new HashMap<>(resolver.getResource(MetricImpl.METRICS_PATH + "/" + metric).getValueMap()));
        }
    }

    private void setCounter(final String metric, final long value) throws Exception
    {
        try (ResourceResolver resolver = this.real.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, "metrics"))) {
            resolver.getResource(MetricImpl.METRICS_PATH + "/" + metric)
                .adaptTo(ModifiableValueMap.class).put("oak:counter", value);
            resolver.commit();
        }
    }
}
