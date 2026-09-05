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

import java.util.List;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.statistics.models.Metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link Aggregations}: the half of a metric a stakeholder will argue with.
 *
 * @version $Id$
 * @since 0.1.0
 */
class AggregationsTest
{
    @Test
    void averagesTheValues()
    {
        assertEquals(4.0, Aggregations.reduce(List.of(2.0, 4.0, 6.0), Metric.MEAN, null));
    }

    @Test
    void takesTheMiddleValueOfAnOddNumberOfThem()
    {
        assertEquals(4.0, Aggregations.reduce(List.of(9.0, 1.0, 4.0), Metric.MEDIAN, null));
    }

    // Between the two middle values rather than arbitrarily one of them, so that a median of two cases
    // does not silently pick a side
    @Test
    void averagesTheTwoMiddleValuesOfAnEvenNumber()
    {
        assertEquals(5.0, Aggregations.reduce(List.of(2.0, 8.0, 4.0, 6.0), Metric.MEDIAN, null));
    }

    @Test
    void sumsForATotal()
    {
        assertEquals(12.0, Aggregations.reduce(List.of(2.0, 4.0, 6.0), Metric.TOTAL, null));
    }

    // At or under the bound counts as within it: a request authorized on the 45th day met a 45-day target
    @Test
    void countsWhatCameInAtOrUnderTheBound()
    {
        assertEquals(75.0,
            Aggregations.reduce(List.of(10.0, 45.0, 44.0, 46.0), Metric.PERCENTAGE_WITHIN, 45.0));
    }

    // A percentage with no bound is unanswerable, and answering 0% or 100% would both be inventions
    @Test
    void refusesAPercentageWithNoBound()
    {
        assertNull(Aggregations.reduce(List.of(1.0, 2.0), Metric.PERCENTAGE_WITHIN, null));
    }

    // Not zero: "no requests were authorized" and "requests took zero days" are different statements
    @Test
    void measuresNothingAsUnknownRatherThanAsZero()
    {
        assertNull(Aggregations.reduce(List.of(), Metric.MEAN, null));
        assertNull(Aggregations.reduce(List.of(), Metric.MEDIAN, null));
        assertNull(Aggregations.reduce(List.of(), Metric.PERCENTAGE_WITHIN, 45.0));
    }

    @Test
    void refusesAnAggregationItDoesNotKnow()
    {
        assertNull(Aggregations.reduce(List.of(1.0), "mode", null));
    }

    @Test
    void isAUtilityClass() throws ReflectiveOperationException
    {
        final var constructor = Aggregations.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
