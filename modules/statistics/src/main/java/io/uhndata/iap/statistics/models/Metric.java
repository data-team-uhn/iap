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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;

/**
 * A Sling Model wrapping a {@code stat:Metric} node: one number an institution reports on, described
 * rather than computed.
 *
 * <p>
 * A definition says three things — which subjects are being measured, what one number to take for each of
 * them, and how those numbers become one. Everything else about a metric follows from those, including its
 * breakdown and its monthly series, which is why they are not separate definitions.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Metric.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Metric extends Content
{
    /** The {@code sling:resourceType} of a {@code stat:Metric} node. */
    public static final String RESOURCE_TYPE = "stat/Metric";

    /** Measure: the days between two recorded actions on the same subject. */
    public static final String DURATION = "duration";

    /** Measure: how many recorded actions of one kind a subject accumulated. */
    public static final String EVENT_COUNT = "eventCount";

    /** Measure: how many children of one resource type the subject has now. */
    public static final String PART_COUNT = "partCount";

    /** Aggregation: the arithmetic mean of the per-subject values. */
    public static final String MEAN = "mean";

    /** Aggregation: the middle value, which is what to ask for when a few slow cases would mislead. */
    public static final String MEDIAN = "median";

    /** Aggregation: what share of subjects came in at or under {@link #getThreshold()}. */
    public static final String PERCENTAGE_WITHIN = "percentageWithin";

    /** Aggregation: the sum, for "how many altogether". */
    public static final String TOTAL = "total";

    /** Breakdown: by whoever performed the action that closed the measurement. */
    public static final String BY_ACTOR = "actor";

    /** Breakdown: by the schema the subject answers. */
    public static final String BY_SCHEMA = "schema";

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String category;

    @ValueMapValue
    private long defaultOrder;

    @ValueMapValue
    private String accessLevel;

    @ValueMapValue
    private String subjectType;

    @ValueMapValue
    private String subjectNodeType;

    @ValueMapValue
    private String schemaPath;

    @ValueMapValue
    private String schemaProperty;

    @ValueMapValue
    private String measure;

    @ValueMapValue
    private String fromOperation;

    @ValueMapValue
    private String fromOutcome;

    @ValueMapValue
    private String toOperation;

    @ValueMapValue
    private String toOutcome;

    @ValueMapValue
    private String countOperation;

    @ValueMapValue
    private String countOutcome;

    @ValueMapValue
    private String partType;

    @ValueMapValue
    private String aggregation;

    @ValueMapValue
    private Double threshold;

    @ValueMapValue
    private String unit;

    @ValueMapValue
    private String breakdownBy;

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
     * @return a description, or {@code null} if not set
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * Groups related metrics where they are shown together.
     *
     * @return a category, or {@code null} if not set
     */
    @Nullable
    public String getCategory()
    {
        return this.category;
    }

    /**
     * The order this metric appears in, among the metrics of its own category.
     *
     * @return the order, {@code 0} by default
     */
    public long getDefaultOrder()
    {
        return this.defaultOrder;
    }

    /**
     * Whether the value may be shown to anybody, or only to administrators. The definition itself is
     * readable either way — what is being restricted is the number, not its description.
     *
     * @return {@code true} if only administrators may see the value
     */
    public boolean isAdminOnly()
    {
        return "admin".equalsIgnoreCase(this.accessLevel);
    }

    /**
     * The resource type of the subject each measurement belongs to, as the history record names it.
     *
     * @return a resource type, e.g. {@code sub/Submission}
     */
    @NotNull
    public String getSubjectType()
    {
        return this.subjectType;
    }

    /**
     * The node type to query when the subjects have to be found in current content rather than in the
     * history — which only {@link #PART_COUNT} needs.
     *
     * <p>Derived from {@link #getSubjectType()} by the naming convention the node types follow, so a
     * definition normally leaves it out; it is here for the types that do not follow it.</p>
     *
     * @return a node type, e.g. {@code sub:Submission}
     */
    @NotNull
    public String getSubjectNodeType()
    {
        return this.subjectNodeType == null ? this.subjectType.replace('/', ':') : this.subjectNodeType;
    }

    /**
     * Restricts the population to subjects answering one schema.
     *
     * @return the schema's path, or {@code null} when every subject counts
     */
    @Nullable
    public String getSchemaPath()
    {
        return this.schemaPath;
    }

    /**
     * The property on the subject pointing at the schema version it answers; the schema is that version's
     * parent. Configurable so that this module needs no knowledge of the module whose entities it measures.
     *
     * @return a property name, {@code schemaVersion} by default
     */
    @NotNull
    public String getSchemaProperty()
    {
        return this.schemaProperty == null ? "schemaVersion" : this.schemaProperty;
    }

    /**
     * What one number to take for each subject.
     *
     * @return {@link #DURATION}, {@link #EVENT_COUNT} or {@link #PART_COUNT}
     */
    @NotNull
    public String getMeasure()
    {
        return this.measure;
    }

    /**
     * The operation whose first occurrence starts the clock, and which defines the population: a subject
     * this never happened to is not being measured at all.
     *
     * @return an operation name, or {@code null}
     */
    @Nullable
    public String getFromOperation()
    {
        return this.fromOperation;
    }

    /**
     * Narrows {@link #getFromOperation()} to actions that ended a particular way.
     *
     * @return an outcome, or {@code null} when any will do
     */
    @Nullable
    public String getFromOutcome()
    {
        return this.fromOutcome;
    }

    /**
     * The operation that stops the clock, counted from its first occurrence at or after the start.
     *
     * @return an operation name, or {@code null}
     */
    @Nullable
    public String getToOperation()
    {
        return this.toOperation;
    }

    /**
     * Narrows {@link #getToOperation()} to actions that ended a particular way.
     *
     * @return an outcome, or {@code null} when any will do
     */
    @Nullable
    public String getToOutcome()
    {
        return this.toOutcome;
    }

    /**
     * The operation counted per subject by {@link #EVENT_COUNT}.
     *
     * @return an operation name, or {@code null}
     */
    @Nullable
    public String getCountOperation()
    {
        return this.countOperation;
    }

    /**
     * Narrows {@link #getCountOperation()} to actions that ended a particular way.
     *
     * @return an outcome, or {@code null} when any will do
     */
    @Nullable
    public String getCountOutcome()
    {
        return this.countOutcome;
    }

    /**
     * The resource type of the children counted by {@link #PART_COUNT}.
     *
     * @return a resource type, or {@code null}
     */
    @Nullable
    public String getPartType()
    {
        return this.partType;
    }

    /**
     * How the per-subject numbers become one number.
     *
     * @return {@link #MEAN}, {@link #MEDIAN}, {@link #PERCENTAGE_WITHIN} or {@link #TOTAL}
     */
    @NotNull
    public String getAggregation()
    {
        return this.aggregation;
    }

    /**
     * The bound {@link #PERCENTAGE_WITHIN} counts against, in this metric's own unit.
     *
     * @return the threshold, or {@code null} when the aggregation does not use one
     */
    @Nullable
    public Double getThreshold()
    {
        return this.threshold;
    }

    /**
     * What the number is counted in, for display.
     *
     * @return a unit, or {@code null} if not set
     */
    @Nullable
    public String getUnit()
    {
        return this.unit;
    }

    /**
     * How the result is split a second way, alongside the overall number.
     *
     * @return {@link #BY_ACTOR}, {@link #BY_SCHEMA}, or {@code null} for no breakdown
     */
    @Nullable
    public String getBreakdownBy()
    {
        return this.breakdownBy;
    }

    /**
     * Whether this definition says enough to be computed at all. A definition that does not is skipped
     * rather than failing the whole dashboard: it is content, and content can be edited into any state.
     *
     * @return {@code true} if the measure has the properties it needs
     */
    public boolean isComputable()
    {
        if (this.label == null || this.subjectType == null || this.measure == null
            || this.aggregation == null) {
            return false;
        }
        return switch (this.measure) {
            case DURATION -> this.fromOperation != null && this.toOperation != null;
            case EVENT_COUNT -> this.fromOperation != null && this.countOperation != null;
            case PART_COUNT -> this.partType != null;
            default -> false;
        };
    }
}
