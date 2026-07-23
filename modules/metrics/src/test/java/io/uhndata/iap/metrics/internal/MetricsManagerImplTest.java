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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.metrics.api.Metric;
import io.uhndata.iap.metrics.api.MetricsException;
import io.uhndata.iap.metrics.api.MetricsManager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MetricsManagerImpl}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MetricsManagerImplTest
{
    private final SlingContext context = new SlingContext();

    private MetricsManagerImpl manager;

    private ResourceResolverFactory factory;

    @BeforeEach
    void setUp() throws Exception
    {
        this.factory = this.context.getService(ResourceResolverFactory.class);
        this.manager = new MetricsManagerImpl();
        inject(this.manager, this.factory);
        try (ResourceResolver resolver = open()) {
            resolver.create(resolver.getResource("/"), "Metrics",
                Map.of("sling:resourceType", "iap/MetricsHomepage"));
            resolver.commit();
        }
    }

    @Test
    void createsMetricWithDefaults() throws Exception
    {
        final Metric metric = this.manager.createMetric("simple").create();

        assertEquals("simple", metric.getName());
        final ValueMap properties = properties("simple");
        assertEquals("iap:Metric", properties.get("jcr:primaryType", String.class));
        assertArrayEquals(new String[] { "mix:atomicCounter" }, properties.get("jcr:mixinTypes", String[].class));
        // The protected sling:resourceType property is not set by the code, it is autocreated by the node type
        assertNull(properties.get("sling:resourceType", String.class));
        assertEquals("simple", properties.get("label", String.class));
        assertEquals("public", properties.get("accessLevel", String.class));
        assertEquals(0L, properties.get("previousValue", Long.class));
        assertNull(properties.get("description", String.class));
        assertNull(properties.get("category", String.class));
        assertNull(properties.get("rolloverSchedule", String.class));
    }

    @Test
    void createsMetricWithFullMetadata() throws Exception
    {
        this.manager.createMetric("full")
            .withLabel("Full metric")
            .withDescription("Counts many things")
            .withCategory("Tests")
            .withAccessLevel(Metric.AccessLevel.ADMIN)
            .withRolloverSchedule("0 0 0 * * ?")
            .create();

        final ValueMap properties = properties("full");
        assertEquals("Full metric", properties.get("label", String.class));
        assertEquals("Counts many things", properties.get("description", String.class));
        assertEquals("Tests", properties.get("category", String.class));
        assertEquals("admin", properties.get("accessLevel", String.class));
        assertEquals("0 0 0 * * ?", properties.get("rolloverSchedule", String.class));
    }

    @Test
    void recreatingUpdatesMetadataAndKeepsTheCounter() throws Exception
    {
        this.manager.createMetric("counted")
            .withLabel("Old label")
            .withDescription("Old description")
            .withCategory("Old category")
            .withRolloverSchedule("0 0 0 * * ?")
            .create();
        // Simulate some activity on the counter
        try (ResourceResolver resolver = open()) {
            final ModifiableValueMap properties =
                resolver.getResource("/Metrics/counted").adaptTo(ModifiableValueMap.class);
            properties.put("oak:counter", 42L);
            properties.put("previousValue", 30L);
            resolver.commit();
        }

        this.manager.createMetric("counted")
            .withLabel("New label")
            .withAccessLevel(Metric.AccessLevel.ADMIN)
            .create();

        final ValueMap properties = properties("counted");
        assertEquals("New label", properties.get("label", String.class));
        assertEquals("admin", properties.get("accessLevel", String.class));
        // Metadata not repeated on the new definition is removed
        assertNull(properties.get("description", String.class));
        assertNull(properties.get("category", String.class));
        assertNull(properties.get("rolloverSchedule", String.class));
        // The counter state is left untouched
        assertEquals(42L, properties.get("oak:counter", Long.class));
        assertEquals(30L, properties.get("previousValue", Long.class));

        // And metadata provided again on a later definition is set back
        this.manager.createMetric("counted")
            .withDescription("Newer description")
            .withCategory("Newer category")
            .withRolloverSchedule("0 0 12 * * ?")
            .create();
        final ValueMap updated = properties("counted");
        assertEquals("Newer description", updated.get("description", String.class));
        assertEquals("Newer category", updated.get("category", String.class));
        assertEquals("0 0 12 * * ?", updated.get("rolloverSchedule", String.class));
    }

    @Test
    void recreatingWithoutLabelRestoresTheDefaultLabel() throws Exception
    {
        this.manager.createMetric("plain").withLabel("Fancy").create();
        this.manager.createMetric("plain").create();
        assertEquals("plain", properties("plain").get("label", String.class));
    }

    @Test
    void rejectsInvalidNames()
    {
        assertThrows(IllegalArgumentException.class, () -> this.manager.createMetric(null));
        assertThrows(IllegalArgumentException.class, () -> this.manager.createMetric(""));
        assertThrows(IllegalArgumentException.class, () -> this.manager.createMetric("has spaces"));
        assertThrows(IllegalArgumentException.class, () -> this.manager.createMetric("../escape"));
        assertThrows(IllegalArgumentException.class, () -> this.manager.createMetric("-leadingDash"));
    }

    @Test
    void refusesToOverwriteNonMetricNodes() throws Exception
    {
        try (ResourceResolver resolver = open()) {
            resolver.create(resolver.getResource("/Metrics"), "policy",
                Map.of("jcr:primaryType", "nt:unstructured"));
            resolver.commit();
        }
        final MetricsManager.MetricBuilder builder = this.manager.createMetric("policy");
        assertThrows(MetricsException.class, builder::create);
    }

    @Test
    void createFailsWithoutTheHomepage() throws Exception
    {
        deleteHomepage();
        final MetricsManager.MetricBuilder builder = this.manager.createMetric("orphan");
        assertThrows(MetricsException.class, builder::create);
    }

    @Test
    void getMetricFindsExistingMetrics()
    {
        this.manager.createMetric("known").create();
        assertTrue(this.manager.getMetric("known").isPresent());
        assertEquals("known", this.manager.getMetric("known").get().getName());
    }

    @Test
    void getMetricIsEmptyForUnknownOrInvalidNames() throws Exception
    {
        assertFalse(this.manager.getMetric("unknown").isPresent());
        assertFalse(this.manager.getMetric(null).isPresent());
        assertFalse(this.manager.getMetric("../Metrics").isPresent());
        // A child of /Metrics which is not an actual metric is not returned either
        try (ResourceResolver resolver = open()) {
            resolver.create(resolver.getResource("/Metrics"), "other",
                Map.of("jcr:primaryType", "nt:unstructured"));
            resolver.commit();
        }
        assertFalse(this.manager.getMetric("other").isPresent());
    }

    @Test
    void getMetricsListsMetricsSortedByName() throws Exception
    {
        this.manager.createMetric("charlie").create();
        this.manager.createMetric("alpha").create();
        this.manager.createMetric("bravo").create();
        // Non-metric children are not listed
        try (ResourceResolver resolver = open()) {
            resolver.create(resolver.getResource("/Metrics"), "other",
                Map.of("jcr:primaryType", "nt:unstructured"));
            resolver.commit();
        }

        final List<Metric> metrics = this.manager.getMetrics();
        assertEquals(List.of("alpha", "bravo", "charlie"), metrics.stream().map(Metric::getName).toList());
    }

    @Test
    void getMetricsFailsWithoutTheHomepage() throws Exception
    {
        deleteHomepage();
        assertThrows(MetricsException.class, () -> this.manager.getMetrics());
    }

    @Test
    void failsWhenTheServiceUserIsNotSetUp() throws Exception
    {
        final ResourceResolverFactory broken = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(broken.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no service user"));
        inject(this.manager, broken);

        assertThrows(MetricsException.class, () -> this.manager.getMetrics());
        assertThrows(MetricsException.class, () -> this.manager.getMetric("any"));
        final MetricsManager.MetricBuilder builder = this.manager.createMetric("any");
        assertThrows(MetricsException.class, builder::create);
    }

    @Test
    void createWrapsCommitFailures() throws Exception
    {
        final ResourceResolver resolver = mockedResolverWithHomepage();
        Mockito.doThrow(new PersistenceException("conflict")).when(resolver).commit();

        final MetricsManager.MetricBuilder builder = this.manager.createMetric("doomed");
        final MetricsException exception = assertThrows(MetricsException.class, builder::create);
        assertTrue(exception.getMessage().contains("doomed"));
    }

    @Test
    void createFailsOnUnmodifiableMetrics() throws Exception
    {
        final ResourceResolver resolver = mockedResolverWithHomepage();
        final Resource existing = Mockito.mock(Resource.class);
        Mockito.when(existing.getValueMap())
            .thenReturn(new ValueMapDecorator(Map.of("jcr:primaryType", "iap:Metric")));
        Mockito.when(existing.adaptTo(ModifiableValueMap.class)).thenReturn(null);
        Mockito.when(resolver.getResource("/Metrics").getChild("stuck")).thenReturn(existing);

        final MetricsManager.MetricBuilder builder = this.manager.createMetric("stuck");
        assertThrows(MetricsException.class, builder::create);
    }

    private ResourceResolver open() throws Exception
    {
        return this.factory.getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, "metrics"));
    }

    private ValueMap properties(final String name) throws Exception
    {
        try (ResourceResolver resolver = open()) {
            return resolver.getResource("/Metrics/" + name).getValueMap();
        }
    }

    private void deleteHomepage() throws Exception
    {
        try (ResourceResolver resolver = open()) {
            resolver.delete(resolver.getResource("/Metrics"));
            resolver.commit();
        }
    }

    private ResourceResolver mockedResolverWithHomepage() throws Exception
    {
        final ResourceResolverFactory mockedFactory = Mockito.mock(ResourceResolverFactory.class);
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        final Resource homepage = Mockito.mock(Resource.class);
        Mockito.when(mockedFactory.getServiceResourceResolver(Mockito.anyMap())).thenReturn(resolver);
        Mockito.when(resolver.getResource("/Metrics")).thenReturn(homepage);
        inject(this.manager, mockedFactory);
        return resolver;
    }

    private void inject(final MetricsManagerImpl target, final ResourceResolverFactory value) throws Exception
    {
        final Field reference = MetricsManagerImpl.class.getDeclaredField("resolverFactory");
        reference.setAccessible(true);
        reference.set(target, value);
    }
}
