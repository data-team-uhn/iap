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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.metrics.api.Metric;
import io.uhndata.iap.metrics.api.MetricsException;
import io.uhndata.iap.metrics.api.MetricsManager;

/**
 * Default implementation of {@link MetricsManager}, storing each metric as a child node of {@code /Metrics}. All
 * repository access happens through a dedicated service user, so callers don't need repository access of their own.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class MetricsManagerImpl implements MetricsManager
{
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    public MetricBuilder createMetric(final String name)
    {
        if (!isValidName(name)) {
            throw new IllegalArgumentException(
                "Invalid metric name: " + name + "; expecting letters, digits, dashes and underscores");
        }
        return new MetricBuilderImpl(name);
    }

    @Override
    public Optional<Metric> getMetric(final String name)
    {
        if (!isValidName(name)) {
            return Optional.empty();
        }
        try (ResourceResolver resolver = MetricImpl.openServiceResolver(this.resolverFactory)) {
            final Resource resource = resolver.getResource(MetricImpl.METRICS_PATH + "/" + name);
            return MetricImpl.isMetric(resource)
                ? Optional.of(new MetricImpl(this.resolverFactory, name))
                : Optional.empty();
        }
    }

    @Override
    public List<Metric> getMetrics()
    {
        try (ResourceResolver resolver = MetricImpl.openServiceResolver(this.resolverFactory)) {
            return StreamSupport.stream(getHomepage(resolver).getChildren().spliterator(), false)
                .filter(MetricImpl::isMetric)
                .map(Resource::getName)
                .sorted()
                .<Metric>map(name -> new MetricImpl(this.resolverFactory, name))
                .toList();
        }
    }

    private static boolean isValidName(final String name)
    {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /**
     * Fetch the node holding all the metrics.
     *
     * @param resolver the session to read through
     * @return the {@code /Metrics} resource
     * @throws MetricsException if the node is missing, meaning the repository wasn't properly initialized
     */
    private Resource getHomepage(final ResourceResolver resolver)
    {
        final Resource homepage = resolver.getResource(MetricImpl.METRICS_PATH);
        if (homepage == null) {
            throw new MetricsException("The metrics homepage is missing from the repository");
        }
        return homepage;
    }

    /**
     * Default implementation of {@link MetricBuilder}, persisting the definition under {@code /Metrics}.
     *
     * @since 0.1.0
     */
    private final class MetricBuilderImpl implements MetricBuilder
    {
        private final String name;

        private String label;

        private String description;

        private String category;

        private Metric.AccessLevel accessLevel = Metric.AccessLevel.PUBLIC;

        private String rolloverSchedule;

        MetricBuilderImpl(final String name)
        {
            this.name = name;
        }

        @Override
        public MetricBuilder withLabel(final String label)
        {
            this.label = label;
            return this;
        }

        @Override
        public MetricBuilder withDescription(final String description)
        {
            this.description = description;
            return this;
        }

        @Override
        public MetricBuilder withCategory(final String category)
        {
            this.category = category;
            return this;
        }

        @Override
        public MetricBuilder withAccessLevel(final Metric.AccessLevel accessLevel)
        {
            this.accessLevel = accessLevel;
            return this;
        }

        @Override
        public MetricBuilder withRolloverSchedule(final String rolloverSchedule)
        {
            this.rolloverSchedule = rolloverSchedule;
            return this;
        }

        @Override
        public Metric create()
        {
            try (ResourceResolver resolver =
                MetricImpl.openServiceResolver(MetricsManagerImpl.this.resolverFactory)) {
                final Resource homepage = getHomepage(resolver);
                final Resource existing = homepage.getChild(this.name);
                if (existing == null) {
                    resolver.create(homepage, this.name, buildProperties());
                } else {
                    updateMetadata(existing);
                }
                resolver.commit();
                return new MetricImpl(MetricsManagerImpl.this.resolverFactory, this.name);
            } catch (final PersistenceException e) {
                throw new MetricsException("Failed to create metric " + this.name + ": " + e.getMessage(), e);
            }
        }

        private Map<String, Object> buildProperties()
        {
            final Map<String, Object> properties = new HashMap<>();
            properties.put("jcr:primaryType", MetricImpl.NODE_TYPE);
            // The repository only maintains the counter when the mixin is explicitly listed on the node
            properties.put("jcr:mixinTypes", new String[] { "mix:atomicCounter" });
            // The protected sling:resourceType property is autocreated by the node type, it cannot be set manually
            properties.put(MetricImpl.PN_LABEL, this.label == null ? this.name : this.label);
            properties.put(MetricImpl.PN_ACCESS_LEVEL, this.accessLevel.asPropertyValue());
            properties.put(MetricImpl.PN_PREVIOUS_VALUE, 0L);
            if (this.description != null) {
                properties.put(MetricImpl.PN_DESCRIPTION, this.description);
            }
            if (this.category != null) {
                properties.put(MetricImpl.PN_CATEGORY, this.category);
            }
            if (this.rolloverSchedule != null) {
                properties.put(MetricImpl.PN_ROLLOVER_SCHEDULE, this.rolloverSchedule);
            }
            return properties;
        }

        private void updateMetadata(final Resource existing)
        {
            if (!MetricImpl.isMetric(existing)) {
                throw new MetricsException(
                    "The path " + existing.getPath() + " is already used by something that is not a metric");
            }
            final ModifiableValueMap properties = existing.adaptTo(ModifiableValueMap.class);
            if (properties == null) {
                throw new MetricsException("The metric " + this.name + " cannot be modified");
            }
            properties.put(MetricImpl.PN_LABEL, this.label == null ? this.name : this.label);
            properties.put(MetricImpl.PN_ACCESS_LEVEL, this.accessLevel.asPropertyValue());
            setOrRemove(properties, MetricImpl.PN_DESCRIPTION, this.description);
            setOrRemove(properties, MetricImpl.PN_CATEGORY, this.category);
            setOrRemove(properties, MetricImpl.PN_ROLLOVER_SCHEDULE, this.rolloverSchedule);
        }

        private void setOrRemove(final ModifiableValueMap properties, final String key, final String value)
        {
            if (value == null) {
                properties.remove(key);
            } else {
                properties.put(key, value);
            }
        }
    }
}
