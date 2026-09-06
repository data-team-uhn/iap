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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.statistics.api.MetricCalculator;
import io.uhndata.iap.statistics.api.MetricValue;
import io.uhndata.iap.statistics.models.Metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MetricStore}: what a refresh keeps, what it clears, and who is shown what.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MetricStoreTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String ROOT = "/Statistics";

    private final SlingContext context = new SlingContext();

    private final MetricStore store = new MetricStore();

    private final MetricCalculator calculator = Mockito.mock(MetricCalculator.class);

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.addModelsForClasses(Content.class, Metric.class);
        this.context.create().resource(ROOT, Map.of(TYPE, "stat/StatisticsHomepage"));
        inject("calculator", this.calculator);
        inject("resolverFactory", factoryReturning(this.context.resourceResolver()));
    }

    @Test
    void keepsWhatEachMetricSaysAndWhenItWasWorkedOut()
    {
        define("first", Map.of("label", "First"));
        define("second", Map.of("label", "Second", "defaultOrder", 5L));
        computes(value("second", 2.0), value("first", 1.0));

        assertNotNull(this.store.refresh());

        final MetricStore.Stored stored = this.store.read(true);
        assertNotNull(stored.computedAt());
        // In the order the definitions ask to be shown in, whatever order they were computed in
        assertEquals(List.of("first", "second"),
            stored.values().stream().map(MetricStoreTest::nameIn).toList());
        assertTrue(stored.values().get(0).contains("\"value\":1.0"));
    }

    // Publishing "the median was 32 days" is not publishing the list it came from, so the numbers are
    // kept where only this module can read them and handed out per metric
    @Test
    void keepsTheAdministratorsMetricsFromEverybodyElse()
    {
        define("open", Map.of("label", "Open"));
        define("restricted", Map.of("label", "Restricted", "accessLevel", "admin"));
        computes(value("open", 1.0), value("restricted", 2.0));
        this.store.refresh();

        assertEquals(List.of("open", "restricted"),
            this.store.read(true).values().stream().map(MetricStoreTest::nameIn).toList());
        assertEquals(List.of("open"),
            this.store.read(false).values().stream().map(MetricStoreTest::nameIn).toList());
    }

    // A definition edited into an unusable state must not go on showing the number it produced before
    // the edit: that is the kind of wrong a reader has no way to notice
    @Test
    void clearsWhatAMetricUsedToSayWhenItCanNoLongerBeWorkedOut()
    {
        define("fading", Map.of("label", "Fading"));
        computes(value("fading", 1.0));
        this.store.refresh();
        assertEquals(1, this.store.read(true).values().size());

        computes();
        this.store.refresh();

        assertTrue(this.store.read(true).values().isEmpty());
    }

    @Test
    void hasNothingToSayBeforeTheFirstRefresh()
    {
        define("waiting", Map.of("label", "Waiting"));

        assertTrue(this.store.isEmpty());
        assertNull(this.store.read(true).computedAt());
        assertTrue(this.store.read(true).values().isEmpty());
    }

    @Test
    void knowsItHasFiguresOnceItHasThem()
    {
        computes();
        this.store.refresh();

        assertFalse(this.store.isEmpty());
    }

    // What an instance looks like before this module's content has been installed. Nothing is recorded,
    // so the next attempt tries again rather than this reading as a refresh that was done
    @Test
    void reportsHavingNowhereToRecordTheFigures() throws Exception
    {
        this.context.resourceResolver().delete(this.context.resourceResolver().getResource(ROOT));
        this.context.resourceResolver().commit();
        computes();

        assertNull(this.store.refresh());
        assertNull(this.store.read(true).computedAt());
        assertTrue(this.store.isEmpty());
    }

    @Test
    void reportsAMetricThatCannotBeWrittenTo() throws Exception
    {
        define("unreachable", Map.of("label", "Unreachable"));
        computes(value("unreachable", 1.0));
        inject("resolverFactory", factoryReturning(hiding(ROOT + "/unreachable")));

        assertNotNull(this.store.refresh());
        assertTrue(this.store.read(true).values().isEmpty());
    }

    // Without the service user there is no answer at all; saying so beats reporting a metric of zero
    @Test
    void survivesAMissingServiceUser() throws Exception
    {
        final ResourceResolverFactory refusing = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(refusing.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no such service user"));
        inject("resolverFactory", refusing);
        computes();

        assertNull(this.store.refresh());
        assertNull(this.store.read(true).computedAt());
        assertTrue(this.store.read(true).values().isEmpty());
        // Not knowing costs one refresh that finds nothing to do, rather than a blank installation
        assertTrue(this.store.isEmpty());
    }

    // The previous figures stay where they are, and they are dated - so a refresh that stops working
    // shows as figures that stop moving rather than as a blank page
    @Test
    void survivesTheValuesNotBeingSaveable() throws Exception
    {
        define("solo", Map.of("label", "Solo"));
        computes(value("solo", 1.0));
        inject("resolverFactory", factoryReturning(refusingToCommit()));

        assertNull(this.store.refresh());
    }

    // --- fixtures -------------------------------------------------------------------------------

    private void define(final String name, final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put(TYPE, Metric.RESOURCE_TYPE);
        this.context.create().resource(ROOT + "/" + name, all);
    }

    private void computes(final MetricValue... values)
    {
        Mockito.when(this.calculator.computeAll()).thenReturn(List.of(values));
    }

    private static MetricValue value(final String name, final double number)
    {
        return MetricValue.of(name, name).valued(number, 1).build();
    }

    private static String nameIn(final String json)
    {
        return json.replaceFirst(".*\"name\":\"([^\"]+)\".*", "$1");
    }

    private void inject(final String field, final Object value) throws ReflectiveOperationException
    {
        final Field declared = MetricStore.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(this.store, value);
    }

    private static ResourceResolverFactory factoryReturning(final ResourceResolver resolver)
        throws LoginException
    {
        final ResourceResolver unclosable = new ResourceResolverWrapper(resolver)
        {
            @Override
            public void close()
            {
                // Closing it would close the context's own resolver, which every assertion reads through
            }
        };
        final ResourceResolverFactory factory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(factory.getServiceResourceResolver(Mockito.anyMap())).thenReturn(unclosable);
        return factory;
    }

    /** A session through which one path cannot be reached, which is what an unwritable node looks like. */
    private ResourceResolver hiding(final String path)
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String requested)
            {
                return path.equals(requested) ? null : super.getResource(requested);
            }
        };
    }

    private ResourceResolver refusingToCommit()
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public void commit() throws PersistenceException
            {
                throw new PersistenceException("the repository is read-only");
            }
        };
    }
}
