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
import java.util.Collection;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.statistics.models.Metric;

/**
 * Turning a set of per-subject numbers into the one number a metric reports.
 *
 * <p>
 * Separate from the reading, and pure, because this is the half a stakeholder will argue with: whether a
 * median of nothing is zero, whether an unfinished case counts against a deadline. Those answers are
 * easier to defend when they can be read in one place.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Aggregations
{
    private Aggregations()
    {
        // Utility class
    }

    /**
     * Reduces the measured values the way the metric asks.
     *
     * @param values what each subject measured
     * @param aggregation how they become one number
     * @param threshold the bound for a percentage, ignored otherwise
     * @return the number, or {@code null} when there was nothing to measure — which is not zero: a mean
     *         over no cases is unknown, and reporting it as zero would be a claim nobody made
     */
    @Nullable
    static Double reduce(@NotNull final Collection<Double> values, @NotNull final String aggregation,
        @Nullable final Double threshold)
    {
        if (values.isEmpty()) {
            return null;
        }
        return switch (aggregation) {
            case Metric.MEAN -> values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            case Metric.MEDIAN -> median(values);
            case Metric.TOTAL -> values.stream().mapToDouble(Double::doubleValue).sum();
            case Metric.PERCENTAGE_WITHIN -> percentageWithin(values, threshold);
            default -> null;
        };
    }

    /**
     * The middle value, averaging the two middle ones when there is an even number of them — so that a
     * median of two cases is between them rather than arbitrarily one of them.
     *
     * @param values what each subject measured
     * @return the median
     */
    private static double median(final Collection<Double> values)
    {
        final List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        final int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
            ? sorted.get(middle)
            : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    /**
     * What share of the values came in at or under the bound, as a percentage.
     *
     * <p>A metric asking this without saying what the bound is cannot be answered, and answering it as
     * 0% or 100% would both be inventions.</p>
     *
     * @param values what each subject measured
     * @param threshold the bound
     * @return the percentage, or {@code null} when no bound was given
     */
    @Nullable
    private static Double percentageWithin(final Collection<Double> values, final Double threshold)
    {
        if (threshold == null) {
            return null;
        }
        final long within = values.stream().filter(value -> value <= threshold).count();
        return 100.0 * within / values.size();
    }
}
