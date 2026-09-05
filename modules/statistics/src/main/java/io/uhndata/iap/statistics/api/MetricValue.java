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
package io.uhndata.iap.statistics.api;

import java.util.ArrayList;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a metric currently says: one number, the same number split by whatever the definition asked it to
 * be split by, and the same number month by month.
 *
 * <p>
 * All three come from one pass over the same per-subject measurements, which is what keeps them from
 * disagreeing — a breakdown that does not add up to the overall figure is the classic way for a report to
 * lose an audience.
 * </p>
 *
 * <p>
 * <strong>Sample size travels with every number.</strong> A median over four
 * cases and a median over four hundred are not the same claim, and a reader who cannot see which one they
 * are looking at cannot tell. It is carried on each slice too, so a reviewer with two reviews is visibly
 * not a trend.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class MetricValue
{
    /**
     * One part of a split: a named subset of the same measurements, aggregated the same way.
     *
     * @param key what this subset is, e.g. a reviewer's name or a month as {@code yyyy-MM}
     * @param value the aggregate over it, or {@code null} when nothing in it could be measured
     * @param sampleSize how many subjects it covers
     * @version $Id$
     * @since 0.1.0
     */
    public record Slice(@NotNull String key, @Nullable Double value, int sampleSize)
    {
    }

    private final String name;

    private final String label;

    private final String description;

    private final String category;

    private final String unit;

    private final Double value;

    private final int sampleSize;

    private final List<Slice> breakdown;

    private final List<Slice> series;

    private MetricValue(final Builder builder)
    {
        this.name = builder.name;
        this.label = builder.label;
        this.description = builder.description;
        this.category = builder.category;
        this.unit = builder.unit;
        this.value = builder.value;
        this.sampleSize = builder.sampleSize;
        this.breakdown = List.copyOf(builder.breakdown);
        this.series = List.copyOf(builder.series);
    }

    /**
     * Starts describing what a metric says.
     *
     * @param name the metric's node name, which is what a client asks for it by
     * @param label what a reader is told it is
     * @return a builder
     */
    @NotNull
    public static Builder of(@NotNull final String name, @NotNull final String label)
    {
        return new Builder(name, label);
    }

    /**
     * The metric's node name.
     *
     * @return the name
     */
    @NotNull
    public String getName()
    {
        return this.name;
    }

    /**
     * What a reader is told this number is.
     *
     * @return the label
     */
    @NotNull
    public String getLabel()
    {
        return this.label;
    }

    /**
     * What it means, in the terms whoever asked for it would use.
     *
     * @return a description, or {@code null}
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * Groups related metrics where they are shown together.
     *
     * @return a category, or {@code null}
     */
    @Nullable
    public String getCategory()
    {
        return this.category;
    }

    /**
     * What the number is counted in.
     *
     * @return a unit, or {@code null}
     */
    @Nullable
    public String getUnit()
    {
        return this.unit;
    }

    /**
     * The number itself.
     *
     * @return the aggregate, or {@code null} when nothing could be measured — which is not zero, and a
     *         display that shows it as zero is telling the reader something untrue
     */
    @Nullable
    public Double getValue()
    {
        return this.value;
    }

    /**
     * How many subjects the number covers.
     *
     * @return the sample size, possibly zero
     */
    public int getSampleSize()
    {
        return this.sampleSize;
    }

    /**
     * The same number split the way the definition asked, largest sample first.
     *
     * @return the slices, empty when the metric asks for no breakdown
     */
    @NotNull
    public List<Slice> getBreakdown()
    {
        return this.breakdown;
    }

    /**
     * The same number month by month, oldest first, keyed {@code yyyy-MM}.
     *
     * <p>Each month holds the subjects whose measurement <em>started</em> in it, so a month stays
     * provisional until everything begun in it has finished. That is the reading the questions ask for —
     * "within 45 days from process initiation" is a claim about a starting cohort — and it is applied to
     * every metric alike rather than being decided per metric.</p>
     *
     * @return the series, empty when nothing could be measured
     */
    @NotNull
    public List<Slice> getSeries()
    {
        return this.series;
    }

    /**
     * This metric as JSON, for a client that will draw it.
     *
     * @return a JSON object
     */
    @NotNull
    public JsonObject toJson()
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("name", this.name)
            .add("label", this.label)
            .add("sampleSize", this.sampleSize);
        addIfPresent(json, "description", this.description);
        addIfPresent(json, "category", this.category);
        addIfPresent(json, "unit", this.unit);
        if (this.value == null) {
            json.addNull("value");
        } else {
            json.add("value", this.value);
        }
        json.add("breakdown", slices(this.breakdown));
        json.add("series", slices(this.series));
        return json.build();
    }

    private static void addIfPresent(final JsonObjectBuilder json, final String key, final String value)
    {
        if (value != null) {
            json.add(key, value);
        }
    }

    private static JsonArrayBuilder slices(final List<Slice> slices)
    {
        final JsonArrayBuilder array = Json.createArrayBuilder();
        slices.forEach(slice -> {
            final JsonObjectBuilder one = Json.createObjectBuilder()
                .add("key", slice.key())
                .add("sampleSize", slice.sampleSize());
            final Double sliceValue = slice.value();
            if (sliceValue == null) {
                one.addNull("value");
            } else {
                one.add("value", sliceValue);
            }
            array.add(one);
        });
        return array;
    }

    /**
     * Builds a {@link MetricValue}.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class Builder
    {
        private final String name;

        private final String label;

        private final List<Slice> breakdown = new ArrayList<>();

        private final List<Slice> series = new ArrayList<>();

        private String description;

        private String category;

        private String unit;

        private Double value;

        private int sampleSize;

        private Builder(final String name, final String label)
        {
            this.name = name;
            this.label = label;
        }

        /**
         * What it means, in the terms whoever asked for it would use.
         *
         * @param text a description
         * @return this builder
         */
        @NotNull
        public Builder describedAs(@Nullable final String text)
        {
            this.description = text;
            return this;
        }

        /**
         * Groups related metrics where they are shown together.
         *
         * @param name a category
         * @return this builder
         */
        @NotNull
        public Builder inCategory(@Nullable final String name)
        {
            this.category = name;
            return this;
        }

        /**
         * What the number is counted in.
         *
         * @param name a unit
         * @return this builder
         */
        @NotNull
        public Builder measuredIn(@Nullable final String name)
        {
            this.unit = name;
            return this;
        }

        /**
         * The number, and how many subjects it covers.
         *
         * @param aggregate the value, {@code null} when nothing could be measured
         * @param subjects how many subjects contributed
         * @return this builder
         */
        @NotNull
        public Builder valued(@Nullable final Double aggregate, final int subjects)
        {
            this.value = aggregate;
            this.sampleSize = subjects;
            return this;
        }

        /**
         * One part of the split.
         *
         * @param slice the slice to add
         * @return this builder
         */
        @NotNull
        public Builder splitBy(@NotNull final Slice slice)
        {
            this.breakdown.add(slice);
            return this;
        }

        /**
         * One month of the series.
         *
         * @param slice the slice to add
         * @return this builder
         */
        @NotNull
        public Builder over(@NotNull final Slice slice)
        {
            this.series.add(slice);
            return this;
        }

        /**
         * The finished value.
         *
         * @return what the metric says
         */
        @NotNull
        public MetricValue build()
        {
            return new MetricValue(this);
        }
    }
}
