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
package io.uhndata.iap.statistics.models;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Metric}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MetricTest
{
    private static final String TYPE = "sling:resourceType";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Metric.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        assertNotNull(metric(Map.of()).getClass());
    }

    @Test
    void exposesWhatTheDefinitionSays()
    {
        final Metric metric = metric(Map.ofEntries(
            Map.entry("label", "Time to authorization"),
            Map.entry("description", "How long it takes"),
            Map.entry("category", "Turnaround"),
            Map.entry("defaultOrder", 30L),
            Map.entry("accessLevel", "admin"),
            Map.entry("subjectType", "sub/Submission"),
            Map.entry("schemaPath", "/Schemas/dataStudy"),
            Map.entry("measure", Metric.DURATION),
            Map.entry("fromOperation", "submit"),
            Map.entry("fromOutcome", "sent"),
            Map.entry("toOperation", "authorize"),
            Map.entry("toOutcome", "granted"),
            Map.entry("aggregation", Metric.MEDIAN),
            Map.entry("threshold", 45.0d),
            Map.entry("unit", "days"),
            Map.entry("breakdownBy", Metric.BY_ACTOR)));

        assertEquals("Time to authorization", metric.getLabel());
        assertEquals("How long it takes", metric.getDescription());
        assertEquals("Turnaround", metric.getCategory());
        assertEquals(30L, metric.getDefaultOrder());
        assertTrue(metric.isAdminOnly());
        assertEquals("sub/Submission", metric.getSubjectType());
        assertEquals("/Schemas/dataStudy", metric.getSchemaPath());
        assertEquals(Metric.DURATION, metric.getMeasure());
        assertEquals("submit", metric.getFromOperation());
        assertEquals("sent", metric.getFromOutcome());
        assertEquals("authorize", metric.getToOperation());
        assertEquals("granted", metric.getToOutcome());
        assertEquals(Metric.MEDIAN, metric.getAggregation());
        assertEquals(45.0d, metric.getThreshold());
        assertEquals("days", metric.getUnit());
        assertEquals(Metric.BY_ACTOR, metric.getBreakdownBy());
    }

    // A cache of the last answer, not a record of it: the number is worked out again from the recorded
    // history on a schedule, and discarding it costs nothing but the wait for the next refresh
    @Test
    void exposesWhatItLastSaidAndWhen()
    {
        final Calendar when = Calendar.getInstance();
        final Metric metric = metric(Map.of("label", "Cached",
            "computedValue", "{\"name\":\"cached\"}", "computedAt", when));

        assertEquals("{\"name\":\"cached\"}", metric.getComputedValue());
        assertEquals(when.getTimeInMillis(), metric.getComputedAt().getTimeInMillis());
    }

    @Test
    void exposesWhatOnlyTheCountingMeasuresUse()
    {
        final Metric metric = metric(Map.of(
            "measure", Metric.EVENT_COUNT, "countOperation", "issue", "countOutcome", "raised",
            "partType", "sub/Answer"));

        assertEquals("issue", metric.getCountOperation());
        assertEquals("raised", metric.getCountOutcome());
        assertEquals("sub/Answer", metric.getPartType());
    }

    @Test
    void leavesTheOptionalPropertiesUnset()
    {
        final Metric metric = metric(Map.of("label", "Bare"));

        assertNull(metric.getDescription());
        assertNull(metric.getCategory());
        assertNull(metric.getSchemaPath());
        assertNull(metric.getThreshold());
        assertNull(metric.getUnit());
        assertNull(metric.getBreakdownBy());
        assertNull(metric.getFromOutcome());
        assertNull(metric.getToOutcome());
        assertNull(metric.getCountOutcome());
        assertNull(metric.getPartType());
        assertNull(metric.getComputedValue());
        assertNull(metric.getComputedAt());
        assertFalse(metric.isAdminOnly());
    }

    // The node type follows the convention, so a definition normally says nothing about it
    @Test
    void derivesTheNodeTypeFromTheResourceType()
    {
        assertEquals("sub:Submission", metric(Map.of("subjectType", "sub/Submission")).getSubjectNodeType());
    }

    @Test
    void letsADefinitionOverrideTheDerivedNodeType()
    {
        assertEquals("nt:unstructured", metric(Map.of(
            "subjectType", "sub/Submission", "subjectNodeType", "nt:unstructured")).getSubjectNodeType());
    }

    @Test
    void namesTheSchemaPropertyItselfOrTakesTheUsualOne()
    {
        assertEquals("schemaVersion", metric(Map.of()).getSchemaProperty());
        assertEquals("answers", metric(Map.of("schemaProperty", "answers")).getSchemaProperty());
    }

    // A definition is content, and content can be edited into any state: one that says too little is
    // skipped rather than taking the whole dashboard down
    @Test
    void refusesADefinitionMissingWhatEveryMeasureNeeds()
    {
        assertFalse(metric(Map.of("subjectType", "s", "measure", Metric.DURATION,
            "aggregation", Metric.MEAN)).isComputable());
        assertFalse(metric(Map.of("label", "L", "measure", Metric.DURATION,
            "aggregation", Metric.MEAN)).isComputable());
        assertFalse(metric(Map.of("label", "L", "subjectType", "s",
            "aggregation", Metric.MEAN)).isComputable());
        assertFalse(metric(Map.of("label", "L", "subjectType", "s",
            "measure", Metric.DURATION)).isComputable());
    }

    @Test
    void refusesAMeasureMissingItsOwnProperties()
    {
        assertFalse(computable(Metric.DURATION, Map.of("fromOperation", "create")).isComputable());
        assertFalse(computable(Metric.DURATION, Map.of("toOperation", "submit")).isComputable());
        assertFalse(computable(Metric.EVENT_COUNT, Map.of("fromOperation", "submit")).isComputable());
        assertFalse(computable(Metric.EVENT_COUNT, Map.of("countOperation", "issue")).isComputable());
        assertFalse(computable(Metric.PART_COUNT, Map.of()).isComputable());
        assertFalse(computable("guesswork", Map.of()).isComputable());
    }

    @Test
    void acceptsADefinitionThatSaysEnough()
    {
        assertTrue(computable(Metric.DURATION,
            Map.of("fromOperation", "create", "toOperation", "submit")).isComputable());
        assertTrue(computable(Metric.EVENT_COUNT,
            Map.of("fromOperation", "submit", "countOperation", "issue")).isComputable());
        assertTrue(computable(Metric.PART_COUNT, Map.of("partType", "sub/Answer")).isComputable());
    }

    private Metric computable(final String measure, final Map<String, Object> extra)
    {
        final Map<String, Object> properties = new HashMap<>(extra);
        properties.put("label", "L");
        properties.put("subjectType", "sub/Submission");
        properties.put("measure", measure);
        properties.put("aggregation", Metric.MEAN);
        return metric(properties);
    }

    private Metric metric(final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put(TYPE, Metric.RESOURCE_TYPE);
        final Resource resource =
            this.context.create().resource("/Statistics/m" + all.hashCode(), all);
        final Metric metric = resource.adaptTo(Metric.class);
        assertNotNull(metric);
        return metric;
    }
}
