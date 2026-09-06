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

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.statistics.api.MetricCalculator;
import io.uhndata.iap.statistics.api.MetricValue;
import io.uhndata.iap.statistics.models.Metric;

/**
 * Keeps what the metrics last said, beside the definitions that produced them.
 *
 * <p>
 * <strong>Reading the metrics and working them out are deliberately different operations, on different
 * clocks.</strong> Working them out reads the whole recorded history, so its cost rises with the age of
 * a deployment; reading them is a property read, and does not. Since the figures sit on the front page,
 * every person opening the application would otherwise pay for the history — so the computation happens
 * on a schedule, off any request, and what a reader gets is the last answer.
 * </p>
 *
 * <p>
 * That makes the figures as old as the last refresh, which is the trade this is: these are monthly
 * cohorts, and a number that moves by a day's worth of work is not a different answer to
 * "what share was authorized within 45 days". <strong>How old they are is published</strong> rather than
 * left for a reader to assume, because a stale number nobody can date is the one that misleads.
 * </p>
 *
 * <p>
 * Storing them in the repository rather than in memory buys three things beyond the wait: they survive a
 * restart, every node of a cluster shows the same figures rather than each holding its own, and past
 * values remain recoverable from the repository's own version history if anyone ever asks what the
 * dashboard said last quarter.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = MetricStore.class)
public class MetricStore
{
    /** The last computed value of one metric, as the JSON a client is served. */
    static final String PN_COMPUTED_VALUE = "computedValue";

    /** When a metric, or the whole set of them, was last worked out. */
    static final String PN_COMPUTED_AT = "computedAt";

    /** The recorded-error detail naming the metric a failure was about. */
    private static final String METRIC_DETAIL = "metric";

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricStore.class);

    /**
     * What the metrics last said, ready to be served.
     *
     * @param computedAt when they were worked out, {@code null} when they never have been
     * @param values what each of them said, as JSON, in the order they are meant to be shown
     * @version $Id$
     * @since 0.1.0
     */
    record Stored(@Nullable Calendar computedAt, @NotNull List<String> values)
    {
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private MetricCalculator calculator;

    /**
     * What the metrics last said.
     *
     * @param includeAdminOnly whether to include the metrics only administrators may see
     * @return the stored values, empty when nothing has been worked out yet
     */
    @NotNull
    Stored read(final boolean includeAdminOnly)
    {
        try (ResourceResolver resolver = Definitions.open(this.resolverFactory)) {
            final List<String> values = Definitions.all(resolver).stream()
                .filter(metric -> includeAdminOnly || !metric.isAdminOnly())
                .map(Metric::getComputedValue)
                .filter(value -> value != null && !value.isBlank())
                .toList();
            return new Stored(lastRefresh(resolver), values);
        } catch (final LoginException e) {
            LOGGER.error("The statistics service user is not available, so the metrics cannot be read: {}",
                e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(MetricStore.class, "read"));
            return new Stored(null, List.of());
        }
    }

    /**
     * Works every metric out again and keeps what they say.
     *
     * <p>Synchronized because two refreshes at once would read the same history twice for the same
     * answer, and then race to write it. The scheduled one runs on a cluster's leader alone; this covers
     * the case of somebody asking for one by hand while it is running.</p>
     *
     * @return when the refresh finished, or {@code null} if it could not be done at all
     */
    @Nullable
    synchronized Calendar refresh()
    {
        final List<MetricValue> computed = this.calculator.computeAll();
        try (ResourceResolver resolver = Definitions.open(this.resolverFactory)) {
            final ModifiableValueMap homepage = writable(resolver, Definitions.ROOT);
            if (homepage == null) {
                // What an instance looks like before this module's content has been installed. Nothing
                // is recorded, so the next run tries again rather than this reading as a done refresh
                LOGGER.warn("There is nothing at {} to record the metrics on", Definitions.ROOT);
                ErrorLogger.logProblem("the statistics homepage is missing",
                    ErrorContext.of(MetricStore.class, "refresh"));
                return null;
            }
            final Calendar now = Calendar.getInstance();
            final Map<String, MetricValue> byName = new HashMap<>();
            computed.forEach(value -> byName.put(value.getName(), value));
            Definitions.all(resolver)
                .forEach(metric -> store(resolver, metric, byName.get(metric.getName()), now));
            homepage.put(PN_COMPUTED_AT, now);
            resolver.commit();
            LOGGER.debug("Worked out {} metrics", computed.size());
            return now;
        } catch (final LoginException e) {
            return failed(e, "the statistics service user is not available");
        } catch (final PersistenceException e) {
            return failed(e, "the computed values could not be saved");
        }
    }

    /**
     * Keeps what one metric said, or clears what it used to say when it can no longer be worked out — a
     * definition edited into an unusable state must not go on showing the number it produced before the
     * edit, which is the kind of wrong a reader has no way to notice.
     *
     * @param resolver the session to write through
     * @param metric the definition
     * @param value what it says now, {@code null} when it could not be worked out
     * @param now when this refresh happened
     */
    private static void store(final ResourceResolver resolver, final Metric metric,
        final MetricValue value, final Calendar now)
    {
        final ModifiableValueMap properties = writable(resolver, metric.getPath());
        if (properties == null) {
            LOGGER.warn("The metric {} cannot be written to, so what it says was not kept",
                metric.getPath());
            ErrorLogger.logProblem("computed metric could not be stored",
                ErrorContext.of(MetricStore.class, "store").with(METRIC_DETAIL, metric.getName()));
        } else if (value == null) {
            properties.remove(PN_COMPUTED_VALUE);
            properties.remove(PN_COMPUTED_AT);
        } else {
            properties.put(PN_COMPUTED_VALUE, value.toJson().toString());
            properties.put(PN_COMPUTED_AT, now);
        }
    }

    /**
     * The properties of one node, ready to be changed.
     *
     * @param resolver the session to write through
     * @param path where the node is
     * @return its properties, or {@code null} if there is no such node or it cannot be written to
     */
    private static ModifiableValueMap writable(final ResourceResolver resolver, final String path)
    {
        final Resource resource = resolver.getResource(path);
        return resource == null ? null : resource.adaptTo(ModifiableValueMap.class);
    }

    /**
     * When the whole set was last worked out.
     *
     * @param resolver the session to read through
     * @return the time of the last refresh, or {@code null} if there has not been one
     */
    private static Calendar lastRefresh(final ResourceResolver resolver)
    {
        final Resource homepage = resolver.getResource(Definitions.ROOT);
        return homepage == null ? null : homepage.getValueMap().get(PN_COMPUTED_AT, Calendar.class);
    }

    /**
     * Reports a refresh that could not happen, leaving the previous figures in place: they are dated, so
     * a refresh that stops working shows as figures that stop moving rather than as a blank page.
     *
     * @param cause what went wrong
     * @param what a phrase naming it
     * @return {@code null}, for the caller to return in turn
     */
    private static Calendar failed(final Exception cause, final String what)
    {
        LOGGER.error("The metrics were not refreshed, {}: {}", what, cause.getMessage(), cause);
        ErrorLogger.logError(cause, ErrorContext.of(MetricStore.class, "refresh"));
        return null;
    }

    /**
     * Whether anything has been worked out yet, which is how a freshly installed instance is told apart
     * from one that has merely restarted.
     *
     * @return {@code true} when there is nothing to show
     */
    boolean isEmpty()
    {
        try (ResourceResolver resolver = Definitions.open(this.resolverFactory)) {
            return lastRefresh(resolver) == null;
        } catch (final LoginException e) {
            LOGGER.error("The statistics service user is not available, so it is not known whether the "
                + "metrics have been worked out: {}", e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(MetricStore.class, "isEmpty"));
            // Answering "nothing yet" costs one refresh that finds it has nothing to do; answering the
            // other way would leave a fresh installation blank until its first scheduled refresh
            return true;
        }
    }
}
