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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
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
import io.uhndata.iap.statistics.api.MetricValue;
import io.uhndata.iap.statistics.models.Metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MetricCalculatorImpl}: which definitions are computed, in which order, and
 * what the three reductions of one set of measurements come out as.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MetricCalculatorImplTest
{
    private static final String TYPE = "sling:resourceType";

    private final SlingContext context = new SlingContext();

    private final MetricCalculatorImpl calculator = new MetricCalculatorImpl();

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.addModelsForClasses(Content.class, Metric.class);
        this.context.create().resource("/Statistics", Map.of(TYPE, "stat/StatisticsHomepage"));
        inject(factoryReturning(answering(this.context.resourceResolver())));
    }

    @Test
    void reducesTheSameMeasurementsThreeWays()
    {
        submissions("one", "two");
        action("a1", "create", "ewong", "one", march(1));
        action("a2", "submit", "achen", "one", march(3));
        action("a3", "create", "ewong", "two", april(1));
        action("a4", "submit", "bpatel", "two", april(9));
        define("timeToSubmit", Map.of("label", "Time to send", "category", "Turnaround",
            "measure", Metric.DURATION, "fromOperation", "create", "toOperation", "submit",
            "aggregation", Metric.MEAN, "unit", "days", "breakdownBy", Metric.BY_ACTOR));

        final List<MetricValue> computed = this.calculator.computeAll();

        assertEquals(1, computed.size());
        final MetricValue value = computed.get(0);
        assertEquals(5.0, value.getValue());
        assertEquals(2, value.getSampleSize());
        assertEquals("days", value.getUnit());
        // The breakdown covers the same two measurements, largest sample first then by name
        assertEquals(2, value.getBreakdown().size());
        assertEquals("achen", value.getBreakdown().get(0).key());
        assertEquals(2.0, value.getBreakdown().get(0).value());
        // And so does the series, by the month each measurement started in, oldest first
        assertEquals(List.of("2026-03", "2026-04"),
            value.getSeries().stream().map(MetricValue.Slice::key).toList());
        assertEquals(8.0, value.getSeries().get(1).value());
    }

    @Test
    void showsThemInTheOrderTheDefinitionsAskFor()
    {
        define("late", Map.of("label", "Late", "category", "B", "defaultOrder", 1L));
        define("early", Map.of("label", "Early", "category", "A", "defaultOrder", 9L));
        define("second", Map.of("label", "Second", "category", "A", "defaultOrder", 20L));
        define("first", Map.of("label", "First", "category", "A", "defaultOrder", 20L));

        assertEquals(List.of("early", "first", "second", "late"),
            this.calculator.computeAll().stream().map(MetricValue::getName).toList());
    }

    // Everything is worked out, including what only administrators may see: who may see which number
    // is decided when it is read, not when it is computed
    @Test
    void computesTheAdministratorsMetricsToo()
    {
        define("open", Map.of("label", "Open"));
        define("restricted", Map.of("label", "Restricted", "accessLevel", "admin"));

        assertEquals(List.of("open", "restricted"),
            this.calculator.computeAll().stream().map(MetricValue::getName).sorted().toList());
    }

    // A definition is content, so it can be edited into a state that says too little; that one is
    // skipped rather than taking every other metric down with it
    @Test
    void skipsADefinitionThatCannotBeComputed()
    {
        define("fine", Map.of("label", "Fine"));
        this.context.create().resource("/Statistics/broken",
            Map.of(TYPE, Metric.RESOURCE_TYPE, "label", "Broken", "subjectType", "sub/Submission"));
        this.context.create().resource("/Statistics/notAMetric", Map.of(TYPE, "nt:unstructured"));

        assertEquals(List.of("fine"),
            this.calculator.computeAll().stream().map(MetricValue::getName).toList());
        assertNull(this.calculator.compute(
            this.context.resourceResolver().getResource("/Statistics/broken").adaptTo(Metric.class)));
    }

    @Test
    void computesOneMetricOnItsOwn()
    {
        submissions("one");
        action("a1", "create", "ewong", "one", march(1));
        action("a2", "submit", "ewong", "one", march(5));
        define("solo", Map.of("label", "Solo", "measure", Metric.DURATION,
            "fromOperation", "create", "toOperation", "submit", "aggregation", Metric.MEAN));

        final MetricValue value = this.calculator.compute(
            this.context.resourceResolver().getResource("/Statistics/solo").adaptTo(Metric.class));

        assertNotNull(value);
        assertEquals(4.0, value.getValue());
    }

    @Test
    void saysNothingWhenThereAreNoDefinitions() throws Exception
    {
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource("/Statistics"));
        this.context.resourceResolver().commit();

        assertTrue(this.calculator.computeAll().isEmpty());
    }

    // The computation runs as a service user; without it there is no answer, and saying so beats
    // reporting a metric of zero
    @Test
    void survivesAMissingServiceUser() throws Exception
    {
        define("solo", Map.of("label", "Solo"));
        final Metric metric =
            this.context.resourceResolver().getResource("/Statistics/solo").adaptTo(Metric.class);
        final ResourceResolverFactory refusing = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(refusing.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no such service user"));
        inject(refusing);

        assertTrue(this.calculator.computeAll().isEmpty());
        assertNull(this.calculator.compute(metric));
    }

    @Test
    void namesAnUnattributedSliceRatherThanDroppingIt()
    {
        submissions("one");
        // No actor on either action, so there is nobody to attribute the measurement to
        this.context.create().resource("/History/aa/bb/cc/a1", Map.of(TYPE, "hist/Action",
            "operation", "create", "actor", "", "occurredAt", march(1)));
        this.context.create().resource("/History/aa/bb/cc/a1/one", Map.of(TYPE, "hist/Entry",
            "subject", "one", "subjectPath", "/Submissions/one", "subjectType", "sub/Submission"));
        action("a2", "submit", null, "one", march(2));
        define("split", Map.of("label", "Split", "measure", Metric.DURATION,
            "fromOperation", "create", "toOperation", "submit", "aggregation", Metric.MEAN,
            "breakdownBy", Metric.BY_ACTOR));

        assertEquals("Unattributed", this.calculator.computeAll().get(0)
            .getBreakdown().get(0).key());
    }

    // --- fixtures -------------------------------------------------------------------------------

    private void define(final String name, final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put(TYPE, Metric.RESOURCE_TYPE);
        all.putIfAbsent("subjectType", "sub/Submission");
        all.putIfAbsent("measure", Metric.EVENT_COUNT);
        all.putIfAbsent("fromOperation", "create");
        all.putIfAbsent("countOperation", "issue");
        all.putIfAbsent("aggregation", Metric.MEAN);
        this.context.create().resource("/Statistics/" + name, all);
    }

    private void submissions(final String... names)
    {
        for (final String name : names) {
            this.context.create().resource("/Submissions/" + name,
                Map.of(TYPE, "sub/Submission", "title", name));
        }
    }

    private void action(final String name, final String operation, final String actor,
        final String subject, final Calendar when)
    {
        final Map<String, Object> properties = new HashMap<>(Map.of(TYPE, "hist/Action",
            "operation", operation, "occurredAt", when));
        if (actor != null) {
            properties.put("actor", actor);
        }
        this.context.create().resource("/History/aa/bb/cc/" + name, properties);
        this.context.create().resource("/History/aa/bb/cc/" + name + "/" + subject,
            Map.of(TYPE, "hist/Entry", "subject", subject, "subjectPath", "/Submissions/" + subject,
                "subjectType", "sub/Submission"));
    }

    private static Calendar march(final int day)
    {
        return on(Calendar.MARCH, day);
    }

    private static Calendar april(final int day)
    {
        return on(Calendar.APRIL, day);
    }

    private static Calendar on(final int month, final int day)
    {
        final Calendar when = Calendar.getInstance();
        when.set(2026, month, day, 9, 0, 0);
        when.set(Calendar.MILLISECOND, 0);
        return when;
    }

    private void inject(final ResourceResolverFactory factory) throws ReflectiveOperationException
    {
        final Field field = MetricCalculatorImpl.class.getDeclaredField("resolverFactory");
        field.setAccessible(true);
        field.set(this.calculator, factory);
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

    /** Answers the history queries out of the created content; the mock repository has no index. */
    private ResourceResolver answering(final ResourceResolver delegate)
    {
        return new ResourceResolverWrapper(delegate)
        {
            @Override
            public Iterator<Resource> findResources(final String query, final String language)
            {
                final List<Resource> found = new ArrayList<>();
                final Resource history = delegate.getResource("/History/aa/bb/cc");
                if (history != null && query.contains("hist:Action")) {
                    history.getChildren().forEach(action -> {
                        final String operation = action.getValueMap().get("operation", String.class);
                        if (operation != null && query.contains("[operation] = '" + operation + "'")) {
                            found.add(action);
                        }
                    });
                }
                return found.iterator();
            }
        };
    }
}
