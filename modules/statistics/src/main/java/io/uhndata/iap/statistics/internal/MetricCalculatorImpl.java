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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.statistics.api.MetricCalculator;
import io.uhndata.iap.statistics.api.MetricValue;
import io.uhndata.iap.statistics.internal.Measurements.Measurement;
import io.uhndata.iap.statistics.models.Metric;

/**
 * Computes what the metrics say, from the record of what happened.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = MetricCalculator.class)
public class MetricCalculatorImpl implements MetricCalculator
{
    /** Where the metric definitions live. */
    static final String STATISTICS_ROOT = "/Statistics";

    private static final String SUBSERVICE = "statistics";

    /** The bucket a measurement with nothing to attribute it to falls into. */
    private static final String UNATTRIBUTED = "Unattributed";

    /**
     * How long a computed set stays good for. These are monthly cohorts, so a figure minutes old is the
     * same figure; what this buys is that the front page does not scan the history once per page view.
     */
    private static final long CACHE_FOR_NANOS = TimeUnit.MINUTES.toNanos(5);

    /** How long a reader waits for a fresh answer before being given the previous one. */
    private static final long WAIT_FOR_FRESH_MILLIS = 2_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricCalculatorImpl.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    private ExecutorService worker;

    private ComputedMetrics cache;

    @Activate
    protected void activate()
    {
        // One thread: two recomputations at once would read the same history twice for the same answer
        this.worker = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "iap-statistics-recompute"));
        this.cache = new ComputedMetrics(this.worker, System::nanoTime);
    }

    @Deactivate
    protected void deactivate()
    {
        this.worker.shutdownNow();
    }

    @Override
    public MetricValue compute(final Metric metric)
    {
        if (!metric.isComputable()) {
            // A definition is content, and content can be edited into any state; one that says too little
            // is skipped rather than taking the dashboard down with it
            LOGGER.warn("The metric {} does not say enough to be computed", metric.getPath());
            return null;
        }
        try (ResourceResolver resolver = readingSession()) {
            return compute(resolver, metric);
        } catch (final LoginException e) {
            LOGGER.error("The statistics service user is not available, so {} cannot be computed: {}",
                metric.getName(), e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(MetricCalculatorImpl.class, "login"));
            return null;
        }
    }

    @Override
    public List<MetricValue> computeAll(final boolean includeAdminOnly)
    {
        // One held set serves both audiences: everything is computed, and what a given reader may not
        // see is dropped on the way out rather than computed separately for them
        return this.cache.get(this::computeEverything, CACHE_FOR_NANOS, WAIT_FOR_FRESH_MILLIS).stream()
            .filter(metric -> includeAdminOnly || !metric.isAdminOnly())
            .toList();
    }

    /**
     * Works out every defined metric, in the order they are meant to be shown: by category, then by
     * their declared order, then by name.
     *
     * @return what they say, skipping any definition that cannot be computed
     */
    private List<MetricValue> computeEverything()
    {
        try (ResourceResolver resolver = readingSession()) {
            final Resource root = resolver.getResource(STATISTICS_ROOT);
            if (root == null) {
                LOGGER.warn("There are no metric definitions at {}", STATISTICS_ROOT);
                return List.of();
            }
            final List<Metric> defined = new ArrayList<>();
            root.getChildren().forEach(child -> {
                final Metric metric = child.adaptTo(Metric.class);
                if (metric != null && metric.isComputable()) {
                    defined.add(metric);
                }
            });
            defined.sort(Comparator
                .comparing((final Metric one) -> Objects.toString(one.getCategory(), ""))
                .thenComparingLong(Metric::getDefaultOrder)
                .thenComparing(Metric::getName));
            return defined.stream().map(metric -> compute(resolver, metric)).toList();
        } catch (final LoginException e) {
            LOGGER.error("The statistics service user is not available, so nothing can be computed: {}",
                e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(MetricCalculatorImpl.class, "login"));
            return List.of();
        }
    }

    /**
     * Computes one metric through an already-open session, so that a dashboard asking for all of them
     * opens one session rather than one per metric.
     *
     * @param resolver the session to read through
     * @param metric the definition to compute
     * @return what it says
     */
    @NotNull
    private MetricValue compute(final ResourceResolver resolver, final Metric metric)
    {
        final List<Measurement> measured = new Measurements(resolver, metric).measure();
        final MetricValue.Builder value = MetricValue.of(metric.getName(), metric.getLabel())
            .describedAs(metric.getDescription())
            .inCategory(metric.getCategory())
            .measuredIn(metric.getUnit())
            .introducedBy(metric.getQualifier(), metric.isProminentLabel())
            .restricted(metric.isAdminOnly())
            .valued(reduce(measured, metric), measured.size());

        breakdown(measured, metric).forEach(value::splitBy);
        series(measured, metric).forEach(value::over);
        return value.build();
    }

    /**
     * The headline number.
     *
     * @param measured what each subject measured
     * @param metric the definition
     * @return the aggregate, or {@code null} when there was nothing to measure
     */
    @Nullable
    private static Double reduce(final List<Measurement> measured, final Metric metric)
    {
        return Aggregations.reduce(measured.stream().map(Measurement::value).toList(),
            metric.getAggregation(), metric.getThreshold());
    }

    /**
     * The same number split the way the definition asked, largest sample first so that the slices a reader
     * should weigh most come first.
     *
     * @param measured what each subject measured
     * @param metric the definition
     * @return the slices, empty when no split was asked for
     */
    private static List<MetricValue.Slice> breakdown(final List<Measurement> measured, final Metric metric)
    {
        if (metric.getBreakdownBy() == null) {
            return List.of();
        }
        return grouped(measured, key -> key.breakdownKey() == null ? UNATTRIBUTED : key.breakdownKey(),
            metric).entrySet().stream()
            .map(Map.Entry::getValue)
            .sorted(Comparator.comparingInt(MetricValue.Slice::sampleSize).reversed()
                .thenComparing(MetricValue.Slice::key))
            .toList();
    }

    /**
     * The same number month by month, oldest first.
     *
     * @param measured what each subject measured
     * @param metric the definition
     * @return the series, empty when nothing could be measured
     */
    private static List<MetricValue.Slice> series(final List<Measurement> measured, final Metric metric)
    {
        final Map<String, MetricValue.Slice> byMonth =
            new TreeMap<>(grouped(measured, MetricCalculatorImpl::month, metric));
        return List.copyOf(byMonth.values());
    }

    /**
     * Reduces the measurements once per group.
     *
     * @param measured what each subject measured
     * @param key which group each belongs to
     * @param metric the definition, for the aggregation
     * @return one slice per group, by key
     */
    private static Map<String, MetricValue.Slice> grouped(final List<Measurement> measured,
        final Function<Measurement, String> key, final Metric metric)
    {
        final Map<String, List<Double>> groups = measured.stream().collect(Collectors.groupingBy(key,
            LinkedHashMap::new, Collectors.mapping(Measurement::value, Collectors.toList())));
        final Map<String, MetricValue.Slice> slices = new LinkedHashMap<>();
        groups.forEach((name, values) -> slices.put(name, new MetricValue.Slice(name,
            Aggregations.reduce(values, metric.getAggregation(), metric.getThreshold()), values.size())));
        return slices;
    }

    /**
     * The month a measurement is counted in: the one it started in, so that a cohort keeps its own figure.
     *
     * @param measurement the measurement
     * @return the month, as {@code yyyy-MM}
     */
    private static String month(final Measurement measurement)
    {
        return String.format("%tY-%tm", measurement.started(), measurement.started());
    }

    /**
     * The privileged session the reading is done through: a metric is an aggregate over records its reader
     * is usually not allowed to see one at a time.
     *
     * @return a session
     * @throws LoginException when the service user is not available
     */
    private ResourceResolver readingSession() throws LoginException
    {
        return this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }
}
