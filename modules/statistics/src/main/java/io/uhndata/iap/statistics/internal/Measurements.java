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
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.statistics.models.Metric;

/**
 * Reads the record of what happened and turns it into one number per subject.
 *
 * <p>
 * Everything a metric reports comes from this one list: the overall figure, the breakdown and the monthly
 * series are three reductions of the same measurements, so they cannot disagree — a breakdown that does not
 * add up to the headline is the usual way a report loses its audience.
 * </p>
 *
 * <p>
 * <strong>Why the history and not the current state.</strong> A workflow instance carries its own start and
 * end times and reading those would be less code, but they are current state: reopening one quietly changes
 * a figure that was published last quarter. The log is append-only, which is what lets a number stay true
 * after somebody has acted on it.
 * </p>
 *
 * <p>One instance per computation — it caches the subject-to-schema lookups, which are asked for twice.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Measurements
{
    /** Where the record of what happened is kept. */
    static final String HISTORY_ROOT = "/History";

    private static final double MILLIS_PER_DAY = 24 * 60 * 60 * 1000.0;

    /**
     * One subject's contribution to a metric.
     *
     * @param subject the subject's identifier, used only to keep subjects apart
     * @param value what it measured
     * @param started when its measurement began, which is the month it is counted in
     * @param breakdownKey which slice it belongs to, or {@code null} when the metric asks for no split
     * @version $Id$
     * @since 0.1.0
     */
    record Measurement(@NotNull String subject, double value, @NotNull Calendar started,
        @Nullable String breakdownKey)
    {
    }

    /**
     * One recorded action, reduced to what a metric needs from it.
     *
     * @param subjectPath where the affected resource was
     * @param when it happened
     * @param actor who did it
     * @version $Id$
     * @since 0.1.0
     */
    private record ActionRecord(String subjectPath, Calendar when, String actor)
    {
    }

    private final ResourceResolver resolver;

    private final Metric metric;

    /** Subject path to schema path, because scoping and splitting both ask, per subject. */
    private final Map<String, String> schemas = new HashMap<>();

    Measurements(@NotNull final ResourceResolver resolver, @NotNull final Metric metric)
    {
        this.resolver = resolver;
        this.metric = metric;
    }

    /**
     * Measures every subject this metric covers.
     *
     * @return one measurement per subject, in no particular order
     */
    @NotNull
    List<Measurement> measure()
    {
        return switch (this.metric.getMeasure()) {
            case Metric.DURATION -> durations();
            case Metric.EVENT_COUNT -> eventCounts();
            case Metric.PART_COUNT -> partCounts();
            default -> List.of();
        };
    }

    /**
     * The days between the action that starts the clock and the one that stops it.
     *
     * <p>
     * A subject whose clock has started and not stopped is normally left out — an unfinished case has no
     * duration to average. The exception is a metric asking what share finished inside a bound: there, a
     * case that has <em>already</em> passed the bound without finishing is counted against it, using the
     * time it has taken so far. Dropping those is how a service-level figure flatters itself, because the
     * cases it would drop are precisely the late ones.
     * </p>
     *
     * @return one measurement per subject that could be measured
     */
    private List<Measurement> durations()
    {
        final Map<String, List<ActionRecord>> starts =
            collect(this.metric.getFromOperation(), this.metric.getFromOutcome());
        final Map<String, List<ActionRecord>> ends =
            collect(this.metric.getToOperation(), this.metric.getToOutcome());
        final Double threshold = this.metric.getThreshold();
        final boolean countOverruns =
            Metric.PERCENTAGE_WITHIN.equals(this.metric.getAggregation()) && threshold != null;
        final long now = System.currentTimeMillis();

        final List<Measurement> measured = new ArrayList<>();
        starts.forEach((subject, started) -> {
            final ActionRecord from = started.get(0);
            final ActionRecord to = ends.getOrDefault(subject, List.of()).stream()
                .filter(candidate -> !candidate.when().before(from.when()))
                .findFirst()
                .orElse(null);
            if (to != null) {
                measured.add(new Measurement(subject,
                    days(from.when().getTimeInMillis(), to.when().getTimeInMillis()), from.when(),
                    breakdownKey(to, from)));
            } else if (countOverruns) {
                final double elapsed = days(from.when().getTimeInMillis(), now);
                if (elapsed > threshold) {
                    measured.add(new Measurement(subject, elapsed, from.when(), breakdownKey(null, from)));
                }
            }
        });
        return measured;
    }

    /**
     * How many actions of one kind each subject accumulated.
     *
     * <p>A subject that accumulated none counts as zero rather than being left out, which is the whole
     * difference between "the average review raises 1.4 issues" and "the average review that raised any
     * issue raised 1.4".</p>
     *
     * @return one measurement per subject in the population
     */
    private List<Measurement> eventCounts()
    {
        final Map<String, List<ActionRecord>> population =
            collect(this.metric.getFromOperation(), this.metric.getFromOutcome());
        final Map<String, List<ActionRecord>> counted =
            collect(this.metric.getCountOperation(), this.metric.getCountOutcome());

        final List<Measurement> measured = new ArrayList<>();
        population.forEach((subject, started) -> {
            final ActionRecord from = started.get(0);
            measured.add(new Measurement(subject, counted.getOrDefault(subject, List.of()).size(),
                from.when(), breakdownKey(null, from)));
        });
        return measured;
    }

    /**
     * How many children of one resource type each subject has.
     *
     * <p>The only measure that asks the current content rather than the history: "how many fields does one
     * of these normally take to fill in" is a question about what a submission looks like now, and the log
     * deliberately records which properties changed rather than what they became.</p>
     *
     * @return one measurement per subject in the population
     */
    private List<Measurement> partCounts()
    {
        final List<Measurement> measured = new ArrayList<>();
        final String wanted = this.metric.getSchemaPath();
        final String partType = this.metric.getPartType();
        // Deliberately uncapped. A cap would turn a metric that is merely slow into one that is quietly
        // wrong, and a wrong number nobody can see is worse than a slow page: what bounds this is the
        // query, which is filtered on an indexed property
        final Iterator<Resource> subjects = this.resolver
            .findResources("SELECT * FROM [" + this.metric.getSubjectNodeType() + "] AS s", "JCR-SQL2");
        while (subjects.hasNext()) {
            final Resource subject = subjects.next();
            final String schema = schemaOf(subject.getPath());
            if (wanted != null && !wanted.equals(schema)) {
                continue;
            }
            long parts = 0;
            for (final Resource part : subject.getChildren()) {
                if (part.isResourceType(partType)) {
                    parts++;
                }
            }
            final Calendar created = subject.getValueMap().get("jcr:created", Calendar.class);
            measured.add(new Measurement(subject.getPath(), parts,
                created == null ? Calendar.getInstance() : created,
                this.metric.getBreakdownBy() == null ? null : schema));
        }
        return measured;
    }

    /**
     * Every recorded action of one kind, by the subject it was about, oldest first.
     *
     * <p>Actions are read rather than entries because the operation is the selective filter and it lives on
     * the action; each action then names the handful of resources it affected.</p>
     *
     * @param operation which operation to look for, {@code null} matching nothing
     * @param outcome an outcome to narrow it to, or {@code null} for any
     * @return the actions, by subject, each subject's own list oldest first
     */
    private Map<String, List<ActionRecord>> collect(final String operation, final String outcome)
    {
        if (operation == null) {
            return Map.of();
        }
        final StringBuilder query = new StringBuilder("SELECT * FROM [hist:Action] AS a WHERE ")
            .append("ISDESCENDANTNODE(a, '").append(HISTORY_ROOT).append("') AND a.[operation] = '")
            .append(escape(operation)).append('\'');
        if (outcome != null) {
            query.append(" AND a.[outcome] = '").append(escape(outcome)).append('\'');
        }
        query.append(" ORDER BY a.[jcr:created] ASC");

        final Map<String, List<ActionRecord>> bySubject = new HashMap<>();
        final Iterator<Resource> actions = this.resolver.findResources(query.toString(), "JCR-SQL2");
        while (actions.hasNext()) {
            record(actions.next(), bySubject);
        }
        // The query orders actions, but one action can affect several subjects, so each subject's own list
        // is sorted rather than assumed to have arrived in order
        bySubject.values().forEach(records -> records.sort(Comparator.comparing(ActionRecord::when)));
        return scoped(bySubject);
    }

    /**
     * Files one action under each subject it affected that this metric is about.
     *
     * @param action the recorded action
     * @param bySubject where to file it
     */
    private void record(final Resource action, final Map<String, List<ActionRecord>> bySubject)
    {
        final ValueMap properties = action.getValueMap();
        // When it happened, not when the row was written. They are the same for anything the engine
        // records as it goes, and differ for a log that was imported or seeded
        final Calendar occurred = properties.get("occurredAt", Calendar.class);
        final Calendar when = occurred == null ? properties.get("jcr:created", Calendar.class) : occurred;
        if (when == null) {
            return;
        }
        final String actor = properties.get("actor", String.class);
        for (final Resource entry : action.getChildren()) {
            final ValueMap fields = entry.getValueMap();
            final String subject = fields.get("subject", String.class);
            if (subject == null
                || !this.metric.getSubjectType().equals(fields.get("subjectType", String.class))) {
                continue;
            }
            bySubject.computeIfAbsent(subject, key -> new ArrayList<>())
                .add(new ActionRecord(fields.get("subjectPath", String.class), when, actor));
        }
    }

    /**
     * Drops the subjects a metric restricted to one schema does not cover.
     *
     * @param bySubject every subject found
     * @return the ones in scope
     */
    private Map<String, List<ActionRecord>> scoped(final Map<String, List<ActionRecord>> bySubject)
    {
        final String wanted = this.metric.getSchemaPath();
        if (wanted == null) {
            return bySubject;
        }
        final Map<String, List<ActionRecord>> kept = new HashMap<>();
        bySubject.forEach((subject, records) -> {
            if (wanted.equals(schemaOf(records.get(0).subjectPath()))) {
                kept.put(subject, records);
            }
        });
        return kept;
    }

    /**
     * Which slice a measurement belongs to.
     *
     * @param closing the action that closed the measurement, or {@code null} when none did
     * @param opening the action that opened it
     * @return the slice's key, or {@code null} when the metric asks for no split
     */
    private String breakdownKey(final ActionRecord closing, final ActionRecord opening)
    {
        if (this.metric.getBreakdownBy() == null) {
            return null;
        }
        if (Metric.BY_ACTOR.equals(this.metric.getBreakdownBy())) {
            // Whoever closed it: "by reviewer" means the reviewer who acted, not whoever raised the request
            return closing == null ? opening.actor() : closing.actor();
        }
        return schemaOf(opening.subjectPath());
    }

    /**
     * Which schema a subject answers: the parent of the version its schema property points at.
     *
     * @param subjectPath where the subject is, possibly {@code null}
     * @return the schema's path, or {@code null} when the subject or its reference cannot be read
     */
    @Nullable
    private String schemaOf(final String subjectPath)
    {
        if (subjectPath == null) {
            return null;
        }
        return this.schemas.computeIfAbsent(subjectPath, this::lookUpSchema);
    }

    /**
     * Reads a subject's schema, without the cache in front of it.
     *
     * @param subjectPath where the subject is
     * @return the schema's path, or {@code null}
     */
    private String lookUpSchema(final String subjectPath)
    {
        final Resource subject = this.resolver.getResource(subjectPath);
        if (subject == null) {
            return null;
        }
        final String versionId =
            subject.getValueMap().get(this.metric.getSchemaProperty(), String.class);
        final Session session = this.resolver.adaptTo(Session.class);
        if (versionId == null || session == null) {
            return null;
        }
        try {
            // Straight through JCR: the version's parent is the schema, and a reference that no longer
            // resolves - or a version somehow at the root - raises the same exception as any other
            // unreadable item, which is the answer either way
            return session.getNodeByIdentifier(versionId).getParent().getPath();
        } catch (final RepositoryException e) {
            // One unreadable subject must not cost every other one its measurement
            return null;
        }
    }

    /**
     * The whole days, and fractions of one, between two moments.
     *
     * @param fromMillis when it started
     * @param toMillis when it stopped
     * @return the elapsed days
     */
    private static double days(final long fromMillis, final long toMillis)
    {
        return (toMillis - fromMillis) / MILLIS_PER_DAY;
    }

    /**
     * Makes a value safe to put in a query literal. These values come from metric definitions, which only
     * an administrator may write, so this guards against a stray apostrophe rather than against an attacker.
     *
     * @param value the value to escape
     * @return the value, with its quotes doubled
     */
    private static String escape(final String value)
    {
        return value.replace("'", "''");
    }
}
