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

import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.metrics.api.Metric;
import io.uhndata.iap.metrics.api.MetricsException;
import io.uhndata.iap.utils.DateUtils;

/**
 * A metric, kept in the {@code metric:Metric} node under {@code /Metrics} that has its name. It reaches the node as
 * the metrics service user.
 *
 * <p>Each read opens a session of its own, unless the metric was made from the properties a listing read. Each change
 * opens a session too. A change that fails to save is tried again on a new session, up to three attempts in all.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class MetricImpl implements Metric
{
    /** The absolute path of the node holding all the metrics. */
    static final String METRICS_PATH = "/Metrics";

    /** The node type of a metric node. */
    static final String NODE_TYPE = "metric:Metric";

    /** The subservice that the metrics service user, {@code iap-metrics}, is mapped to. */
    static final String SUBSERVICE_NAME = "metrics";

    /** The name of the property holding the human-readable name. */
    static final String PN_LABEL = "label";

    /** The name of the property holding the description. */
    static final String PN_DESCRIPTION = "description";

    /** The name of the property holding the category. */
    static final String PN_CATEGORY = "category";

    /** The name of the property holding the placement within the category. */
    static final String PN_DEFAULT_ORDER = "defaultOrder";

    /** The name of the property holding the access level. */
    static final String PN_ACCESS_LEVEL = "accessLevel";

    /** The name of the property holding the count at the last roll-over. */
    static final String PN_PREVIOUS_VALUE = "previousValue";

    /** The name of the property holding the amount counted in the last closed period. */
    static final String PN_LAST_DELTA = "lastDelta";

    /** The name of the property holding the last increment date. */
    static final String PN_LAST_UPDATED = "lastUpdated";

    /** The name of the property holding the last roll-over date. */
    static final String PN_LAST_ROLLOVER = "lastRollover";

    /** The name of the property holding the Quartz cron expression for automatic roll-overs. */
    static final String PN_ROLLOVER_SCHEDULE = "rolloverSchedule";

    /** The name of the protected property in which Oak keeps the count. */
    static final String PN_COUNTER = "oak:counter";

    /** The name of the property that asks Oak to add to the count. Oak removes it as it saves the change. */
    static final String PN_INCREMENT = "oak:increment";

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricImpl.class);

    /** How many times an update is attempted before giving up. */
    private static final int MAX_ATTEMPTS = 3;

    private final ResourceResolverFactory resolverFactory;

    private final String name;

    /** The properties a listing read, which reads are answered from; {@code null} if each read opens a session. */
    private final ValueMap snapshot;

    /**
     * A metric that reads the repository on every call.
     *
     * @param resolverFactory the factory that opens a service session for each read and each change
     * @param name the metric's name, which is also the name of its node
     */
    MetricImpl(final ResourceResolverFactory resolverFactory, final String name)
    {
        this.resolverFactory = resolverFactory;
        this.name = name;
        this.snapshot = null;
    }

    /**
     * A metric that answers reads from the properties a listing read, and opens a session only to change them.
     *
     * @param resolverFactory the factory that opens a service session for each change
     * @param name the metric's name, which is also the name of its node
     * @param properties the properties of the metric's node; they are copied, since the session they were read in
     *            closes before the metric is used
     */
    MetricImpl(final ResourceResolverFactory resolverFactory, final String name, final ValueMap properties)
    {
        this.resolverFactory = resolverFactory;
        this.name = name;
        this.snapshot = new ValueMapDecorator(Collections.unmodifiableMap(new HashMap<>(properties)));
    }

    /**
     * Whether a resource is a metric's node, and not something else under {@code /Metrics} such as its access control
     * policy.
     *
     * @param resource the resource; {@code null} if there is none
     * @return {@code true} if the resource is a {@code metric:Metric} node
     */
    static boolean isMetric(final Resource resource)
    {
        return resource != null && NODE_TYPE.equals(resource.getValueMap().get("jcr:primaryType", String.class));
    }

    /**
     * Open a new session as the metrics service user.
     *
     * @param resolverFactory the factory to open it with
     * @return the resource resolver, for the caller to close
     * @throws MetricsException if the service user cannot log in
     */
    static ResourceResolver openServiceResolver(final ResourceResolverFactory resolverFactory)
    {
        try {
            return resolverFactory.getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE,
                SUBSERVICE_NAME));
        } catch (final LoginException e) {
            throw new MetricsException("Failed to access the repository: " + e.getMessage(), e);
        }
    }

    @Override
    public String getName()
    {
        return this.name;
    }

    @Override
    public String getLabel()
    {
        return read(properties -> properties.get(PN_LABEL, this.name));
    }

    @Override
    public String getDescription()
    {
        return read(properties -> properties.get(PN_DESCRIPTION, String.class));
    }

    @Override
    public String getCategory()
    {
        return read(MetricImpl::category);
    }

    @Override
    public long getDefaultOrder()
    {
        return read(MetricImpl::defaultOrder);
    }

    @Override
    public AccessLevel getAccessLevel()
    {
        return read(properties -> AccessLevel.fromPropertyValue(properties.get(PN_ACCESS_LEVEL, String.class)));
    }

    @Override
    public long getCurrentValue()
    {
        return read(MetricImpl::currentValue);
    }

    @Override
    public long getPreviousValue()
    {
        return read(MetricImpl::previousValue);
    }

    @Override
    public long getCurrentDelta()
    {
        return read(MetricImpl::currentDelta);
    }

    @Override
    public long getLastDelta()
    {
        return read(properties -> properties.get(PN_LAST_DELTA, 0L));
    }

    @Override
    public ZonedDateTime getLastUpdated()
    {
        return read(properties -> toDateTime(properties.get(PN_LAST_UPDATED, Calendar.class)));
    }

    @Override
    public ZonedDateTime getLastRollover()
    {
        return read(properties -> toDateTime(properties.get(PN_LAST_ROLLOVER, Calendar.class)));
    }

    @Override
    public String getRolloverSchedule()
    {
        return read(properties -> properties.get(PN_ROLLOVER_SCHEDULE, String.class));
    }

    @Override
    public void increment()
    {
        increment(1);
    }

    @Override
    public void increment(final long amount)
    {
        if (amount == 0) {
            return;
        }
        try {
            write(properties -> {
                properties.put(PN_INCREMENT, amount);
                properties.put(PN_LAST_UPDATED, Calendar.getInstance());
            });
        } catch (final MetricsException e) {
            // Recorded because the caller is never told and the count is lost for good. Once per failure, since the
            // recorder folds repeats into one tally and that tally is how many counts were lost.
            LOGGER.error("Failed to increment metric {}: {}", this.name, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(MetricImpl.class, "increment").with("metric", this.name));
        }
    }

    @Override
    public void rollOver()
    {
        write(properties -> {
            properties.put(PN_LAST_DELTA, currentDelta(properties));
            properties.put(PN_PREVIOUS_VALUE, currentValue(properties));
            properties.put(PN_LAST_ROLLOVER, Calendar.getInstance());
        });
    }

    @Override
    public JsonObject toJson()
    {
        return read(properties -> {
            final JsonObjectBuilder json = Json.createObjectBuilder()
                .add("name", this.name)
                .add("label", properties.get(PN_LABEL, this.name))
                .add("description", properties.get(PN_DESCRIPTION, ""))
                .add("category", Objects.requireNonNullElse(category(properties), ""))
                .add("defaultOrder", defaultOrder(properties))
                .add("accessLevel",
                    AccessLevel.fromPropertyValue(properties.get(PN_ACCESS_LEVEL, String.class)).asPropertyValue())
                .add("currentValue", currentValue(properties))
                .add("previousValue", previousValue(properties))
                .add("currentDelta", currentDelta(properties))
                .add("lastDelta", properties.get(PN_LAST_DELTA, 0L));
            addDate(json, PN_LAST_UPDATED, properties.get(PN_LAST_UPDATED, Calendar.class));
            addDate(json, PN_LAST_ROLLOVER, properties.get(PN_LAST_ROLLOVER, Calendar.class));
            final String schedule = properties.get(PN_ROLLOVER_SCHEDULE, String.class);
            if (schedule != null) {
                json.add(PN_ROLLOVER_SCHEDULE, schedule);
            }
            return json.build();
        });
    }

    /**
     * Read a value of this metric: from the properties a listing read if there are any, and from the repository
     * otherwise.
     *
     * @param <T> the type of the value
     * @param extractor computes the value from the properties of the metric's node
     * @return the value
     * @throws MetricsException if the metric's node cannot be read
     */
    private <T> T read(final Function<ValueMap, T> extractor)
    {
        if (this.snapshot != null) {
            return extractor.apply(this.snapshot);
        }
        try (ResourceResolver resolver = openServiceResolver(this.resolverFactory)) {
            return extractor.apply(getMetricResource(resolver).getValueMap());
        }
    }

    /**
     * Change the metric's node and save it. A save that fails is tried again on a new session, up to
     * {@link #MAX_ATTEMPTS} attempts in all.
     *
     * @param changes sets the new values on the node's properties
     * @throws MetricsException if the node is missing or cannot be modified, or if the last attempt fails to save
     */
    private void write(final Consumer<ModifiableValueMap> changes)
    {
        for (int attempt = 1;; ++attempt) {
            try (ResourceResolver resolver = openServiceResolver(this.resolverFactory)) {
                final ModifiableValueMap properties = getMetricResource(resolver).adaptTo(ModifiableValueMap.class);
                if (properties == null) {
                    throw new MetricsException("The metric " + this.name + " cannot be modified");
                }
                changes.accept(properties);
                resolver.commit();
                return;
            } catch (final PersistenceException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new MetricsException(
                        "Failed to update metric " + this.name + ": " + e.getMessage(), e);
                }
                LOGGER.debug("Retrying the update of metric {} after a concurrent change", this.name);
            }
        }
    }

    /**
     * The node of this metric.
     *
     * @param resolver the session to read it in
     * @return the metric's resource
     * @throws MetricsException if no metric's node has this name
     */
    private Resource getMetricResource(final ResourceResolver resolver)
    {
        final Resource resource = resolver.getResource(METRICS_PATH + "/" + this.name);
        if (!isMetric(resource)) {
            throw new MetricsException("The metric " + this.name + " no longer exists");
        }
        return resource;
    }

    private static long currentValue(final ValueMap properties)
    {
        return properties.get(PN_COUNTER, 0L);
    }

    private static long previousValue(final ValueMap properties)
    {
        return properties.get(PN_PREVIOUS_VALUE, 0L);
    }

    /**
     * The category of a metric. A blank category groups nothing, so it counts as none.
     *
     * @param properties the properties of the metric's node
     * @return the category; {@code null} if it is not set or is blank
     */
    private static String category(final ValueMap properties)
    {
        final String category = properties.get(PN_CATEGORY, String.class);
        return category == null || category.isBlank() ? null : category;
    }

    private static long defaultOrder(final ValueMap properties)
    {
        return properties.get(PN_DEFAULT_ORDER, 0L);
    }

    private static long currentDelta(final ValueMap properties)
    {
        return currentValue(properties) - previousValue(properties);
    }

    private static ZonedDateTime toDateTime(final Calendar date)
    {
        return date == null ? null : date.toInstant().atZone(date.getTimeZone().toZoneId());
    }

    private static void addDate(final JsonObjectBuilder json, final String key, final Calendar date)
    {
        if (date != null) {
            json.add(key, DateUtils.toString(date));
        }
    }
}
