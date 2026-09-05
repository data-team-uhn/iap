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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.statistics.internal.Measurements.Measurement;
import io.uhndata.iap.statistics.models.Metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Measurements}: what each subject contributes, and which subjects contribute
 * at all.
 *
 * <p>The mock repository has no index behind {@code findResources}, so the queries are answered from
 * the created content by a resolver that reads the statement rather than executing it. What is being
 * tested is what this class does with the rows, not the statement that fetched them.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MeasurementsTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String SUBMISSION = "sub/Submission";

    private static final String ONE = "one";

    private static final String CREATE = "create";

    private static final String SUBMIT = "submit";

    private static final String REQUESTER = "ewong";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private Calendar day1;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Metric.class);
        this.day1 = Calendar.getInstance();
        this.day1.set(2026, Calendar.MARCH, 1, 9, 0, 0);
        this.day1.set(Calendar.MILLISECOND, 0);
    }

    @Test
    void measuresTheDaysBetweenTwoActions()
    {
        submission(ONE, null);
        action("a1", CREATE, null, REQUESTER, ONE, days(0));
        action("a2", SUBMIT, null, REQUESTER, ONE, days(3));

        final List<Measurement> measured = measure(duration(CREATE, SUBMIT, Metric.MEAN));

        assertEquals(1, measured.size());
        assertEquals(3.0, measured.get(0).value());
    }

    // The first start and the first end at or after it: a request sent twice does not restart a
    // measurement that has already closed
    @Test
    void takesTheFirstStartAndTheFirstEndAfterIt()
    {
        submission(ONE, null);
        action("a1", CREATE, null, REQUESTER, ONE, days(0));
        action("a2", CREATE, null, REQUESTER, ONE, days(5));
        action("a3", SUBMIT, null, REQUESTER, ONE, days(2));
        action("a4", SUBMIT, null, REQUESTER, ONE, days(9));

        assertEquals(2.0, measure(duration(CREATE, SUBMIT, Metric.MEAN)).get(0).value());
    }

    @Test
    void ignoresAnEndingThatCameBeforeTheStart()
    {
        submission(ONE, null);
        action("a1", SUBMIT, null, REQUESTER, ONE, days(4));
        action("a2", "authorize", null, "authority", ONE, days(1));

        assertTrue(measure(duration(SUBMIT, "authorize", Metric.MEAN)).isEmpty());
    }

    // An unfinished case has no duration to average, so it is not averaged
    @Test
    void leavesAnUnfinishedCaseOutOfAnAverage()
    {
        submission(ONE, null);
        action("a1", CREATE, null, REQUESTER, ONE, days(0));

        assertTrue(measure(duration(CREATE, SUBMIT, Metric.MEAN)).isEmpty());
    }

    // ... but a case that has already overrun the bound counts against a service level, using the time
    // it has taken so far. Dropping these is how such a figure flatters itself
    @Test
    void countsAnOverrunningCaseAgainstAServiceLevel()
    {
        submission(ONE, null);
        final Calendar longAgo = Calendar.getInstance();
        longAgo.add(Calendar.DAY_OF_YEAR, -90);
        action("a1", CREATE, null, REQUESTER, ONE, longAgo);

        final Metric metric = metric(Map.of("measure", Metric.DURATION, "fromOperation", CREATE,
            "toOperation", "authorize", "aggregation", Metric.PERCENTAGE_WITHIN, "threshold", 45.0d));
        final List<Measurement> measured = measure(metric);

        assertEquals(1, measured.size());
        assertTrue(measured.get(0).value() > 45.0);
    }

    @Test
    void leavesACaseStillInsideTheBoundUndecided()
    {
        submission(ONE, null);
        final Calendar recently = Calendar.getInstance();
        recently.add(Calendar.DAY_OF_YEAR, -2);
        action("a1", CREATE, null, REQUESTER, ONE, recently);

        assertTrue(measure(metric(Map.of("measure", Metric.DURATION, "fromOperation", CREATE,
            "toOperation", "authorize", "aggregation", Metric.PERCENTAGE_WITHIN,
            "threshold", 45.0d))).isEmpty());
    }

    @Test
    void narrowsAnActionToItsOutcome()
    {
        submission(ONE, null);
        action("a1", SUBMIT, null, REQUESTER, ONE, days(0));
        action("a2", "review", "rejected", "achen", ONE, days(1));
        action("a3", "review", "approved", "achen", ONE, days(4));

        final Metric metric = metric(Map.of("measure", Metric.DURATION, "fromOperation", SUBMIT,
            "toOperation", "review", "toOutcome", "approved", "aggregation", Metric.MEAN));

        assertEquals(4.0, measure(metric).get(0).value());
    }

    // A subject that accumulated none counts as zero, which is the difference between "the average
    // request draws 1.4 issues" and "the average request that drew any drew 1.4"
    @Test
    void countsEventsIncludingTheSubjectsWithNone()
    {
        submission(ONE, null);
        submission("two", null);
        action("a1", SUBMIT, null, REQUESTER, ONE, days(0));
        action("a2", SUBMIT, null, REQUESTER, "two", days(0));
        action("a3", "issue", null, "achen", ONE, days(1));
        action("a4", "issue", null, "achen", ONE, days(2));

        final List<Measurement> measured = measure(metric(Map.of("measure", Metric.EVENT_COUNT,
            "fromOperation", SUBMIT, "countOperation", "issue", "aggregation", Metric.MEAN)));

        assertEquals(2, measured.size());
        assertEquals(2.0, measured.stream().mapToDouble(Measurement::value).max().orElseThrow());
        assertEquals(0.0, measured.stream().mapToDouble(Measurement::value).min().orElseThrow());
    }

    @Test
    void countsThePartsASubjectHasNow()
    {
        final Resource one = submission(ONE, null);
        modify(one, "jcr:created", days(0));
        this.context.create().resource(one.getPath() + "/a1", Map.of(TYPE, "sub/Answer"));
        this.context.create().resource(one.getPath() + "/a2", Map.of(TYPE, "sub/Answer"));
        this.context.create().resource(one.getPath() + "/other", Map.of(TYPE, "sub/Document"));

        final List<Measurement> measured = measure(metric(Map.of("measure", Metric.PART_COUNT,
            "partType", "sub/Answer", "subjectNodeType", "nt:unstructured",
            "aggregation", Metric.MEDIAN)));

        assertEquals(1, measured.size());
        assertEquals(2.0, measured.get(0).value());
    }

    @Test
    void splitsByWhoeverClosedTheMeasurement()
    {
        submission(ONE, null);
        action("a1", SUBMIT, null, REQUESTER, ONE, days(0));
        action("a2", "review", "approved", "achen", ONE, days(2));

        final Metric metric = metric(Map.of("measure", Metric.DURATION, "fromOperation", SUBMIT,
            "toOperation", "review", "aggregation", Metric.MEAN, "breakdownBy", Metric.BY_ACTOR));

        assertEquals("achen", measure(metric).get(0).breakdownKey());
    }

    // Nobody closed it, so the opener is the only person it can be attributed to
    @Test
    void fallsBackToTheOpenerWhenNobodyClosedIt()
    {
        submission(ONE, null);
        final Calendar longAgo = Calendar.getInstance();
        longAgo.add(Calendar.DAY_OF_YEAR, -90);
        action("a1", CREATE, null, REQUESTER, ONE, longAgo);

        final Metric metric = metric(Map.of("measure", Metric.DURATION, "fromOperation", CREATE,
            "toOperation", "authorize", "aggregation", Metric.PERCENTAGE_WITHIN, "threshold", 45.0d,
            "breakdownBy", Metric.BY_ACTOR));

        assertEquals(REQUESTER, measure(metric).get(0).breakdownKey());
    }

    @Test
    void splitsBySchemaAndScopesToOne()
    {
        schema("fast");
        schema("slow");
        submission(ONE, "fast");
        submission("two", "slow");
        action("a1", CREATE, null, REQUESTER, ONE, days(0));
        action("a2", SUBMIT, null, REQUESTER, ONE, days(1));
        action("a3", CREATE, null, REQUESTER, "two", days(0));
        action("a4", SUBMIT, null, REQUESTER, "two", days(2));

        final Map<String, Object> shared = Map.of("measure", Metric.DURATION,
            "fromOperation", CREATE, "toOperation", SUBMIT, "aggregation", Metric.MEAN);
        final Map<String, Object> split = new HashMap<>(shared);
        split.put("breakdownBy", Metric.BY_SCHEMA);
        assertEquals(2, measure(metric(split)).size());
        assertTrue(measure(metric(split)).stream()
            .anyMatch(one -> "/Schemas/fast".equals(one.breakdownKey())));

        final Map<String, Object> scoped = new HashMap<>(shared);
        scoped.put("schemaPath", "/Schemas/fast");
        final List<Measurement> only = measure(metric(scoped));
        assertEquals(1, only.size());
        assertEquals(1.0, only.get(0).value());
    }

    @Test
    void scopesThePartCountToOneSchemaToo()
    {
        schema("fast");
        schema("slow");
        final Resource one = submission(ONE, "fast");
        final Resource two = submission("two", "slow");
        this.context.create().resource(one.getPath() + "/a1", Map.of(TYPE, "sub/Answer"));
        this.context.create().resource(two.getPath() + "/a1", Map.of(TYPE, "sub/Answer"));
        this.context.create().resource(two.getPath() + "/a2", Map.of(TYPE, "sub/Answer"));

        final List<Measurement> measured = measure(metric(Map.of("measure", Metric.PART_COUNT,
            "partType", "sub/Answer", "subjectNodeType", "nt:unstructured", "schemaPath", "/Schemas/slow",
            "aggregation", Metric.MEDIAN, "breakdownBy", Metric.BY_SCHEMA)));

        assertEquals(1, measured.size());
        assertEquals(2.0, measured.get(0).value());
        assertEquals("/Schemas/slow", measured.get(0).breakdownKey());
    }

    // The bound is what a percentage is measured against; without one there is nothing to count an
    // unfinished case against either, so it is simply left out like any other unfinished case
    @Test
    void leavesOverrunsOutOfAPercentageWithNoBound()
    {
        submission(ONE, null);
        final Calendar longAgo = Calendar.getInstance();
        longAgo.add(Calendar.DAY_OF_YEAR, -90);
        action("a1", CREATE, null, REQUESTER, ONE, longAgo);

        assertTrue(measure(metric(Map.of("measure", Metric.DURATION, "fromOperation", CREATE,
            "toOperation", "authorize", "aggregation", Metric.PERCENTAGE_WITHIN))).isEmpty());
    }

    @Test
    void measuresNothingForAMeasureItDoesNotKnow()
    {
        assertTrue(measure(metric(Map.of("measure", "guesswork", "aggregation", Metric.MEAN))).isEmpty());
    }

    @Test
    void readsNothingWhenTheMetricNamesNoOperation()
    {
        assertTrue(measure(metric(Map.of("measure", Metric.DURATION, "toOperation", SUBMIT,
            "aggregation", Metric.MEAN))).isEmpty());
    }

    @Test
    void passesOverAnActionThatSaysNothingUsable()
    {
        submission(ONE, null);
        // No date at all, an entry for another kind of thing, and an entry naming no subject
        final Resource undated = this.context.create().resource("/History/aa/bb/cc/undated",
            Map.of(TYPE, "hist/Action", "operation", CREATE, "actor", REQUESTER));
        this.context.create().resource(undated.getPath() + "/e", Map.of(TYPE, "hist/Entry",
            "subject", ONE, "subjectType", SUBMISSION));
        final Resource dated = action("dated", CREATE, null, REQUESTER, ONE, days(0));
        this.context.create().resource(dated.getPath() + "/other", Map.of(TYPE, "hist/Entry",
            "subject", "elsewhere", "subjectType", "wf/WorkflowInstance"));
        this.context.create().resource(dated.getPath() + "/nameless", Map.of(TYPE, "hist/Entry",
            "subjectType", SUBMISSION));
        action("closing", SUBMIT, null, REQUESTER, ONE, days(1));

        assertEquals(1, measure(duration(CREATE, SUBMIT, Metric.MEAN)).size());
    }

    @Test
    void readsNoSchemaWhenThereIsNothingToReadItFrom() throws Exception
    {
        schema("fast");
        submission("bare", null);
        submission("gone", "fast");
        action("a1", CREATE, null, REQUESTER, "bare", days(0));
        action("a2", SUBMIT, null, REQUESTER, "bare", days(1));
        action("a3", CREATE, null, REQUESTER, "gone", days(0));
        action("a4", SUBMIT, null, REQUESTER, "gone", days(1));
        // A reference to something that is not there any more
        this.context.resourceResolver().getResource("/Submissions/gone")
            .adaptTo(javax.jcr.Node.class)
            .setProperty("schemaVersion", "00000000-0000-0000-0000-000000000000");
        this.context.resourceResolver().commit();

        final Map<String, Object> split = Map.of("measure", Metric.DURATION, "fromOperation", CREATE,
            "toOperation", SUBMIT, "aggregation", Metric.MEAN, "breakdownBy", Metric.BY_SCHEMA);

        assertTrue(measure(metric(split)).stream().allMatch(one -> one.breakdownKey() == null));
    }

    // An entry that names the subject but not where it was: the record outlives its subject, so a path
    // is the one thing about it that can go missing
    @Test
    void readsNoSchemaWhenTheEntryDoesNotSayWhereTheSubjectWas()
    {
        this.context.create().resource("/History/aa/bb/cc/a1", Map.of(TYPE, "hist/Action",
            "operation", CREATE, "actor", REQUESTER, "occurredAt", days(0)));
        this.context.create().resource("/History/aa/bb/cc/a1/one", Map.of(TYPE, "hist/Entry",
            "subject", ONE, "subjectType", SUBMISSION));
        this.context.create().resource("/History/aa/bb/cc/a2", Map.of(TYPE, "hist/Action",
            "operation", SUBMIT, "actor", REQUESTER, "occurredAt", days(2)));
        this.context.create().resource("/History/aa/bb/cc/a2/one", Map.of(TYPE, "hist/Entry",
            "subject", ONE, "subjectType", SUBMISSION));

        final Metric metric = metric(Map.of("measure", Metric.DURATION, "fromOperation", CREATE,
            "toOperation", SUBMIT, "aggregation", Metric.MEAN, "breakdownBy", Metric.BY_SCHEMA));

        assertNull(measure(metric).get(0).breakdownKey());
    }

    @Test
    void readsNoSchemaWithoutASession()
    {
        schema("fast");
        submission(ONE, "fast");
        action("a1", CREATE, null, REQUESTER, ONE, days(0));
        action("a2", SUBMIT, null, REQUESTER, ONE, days(1));

        final ResourceResolver sessionless =
            new ResourceResolverWrapper(answering(this.context.resourceResolver()))
            {
                @Override
                public <T> T adaptTo(final Class<T> type)
                {
                    return type == Session.class ? null : super.adaptTo(type);
                }
            };
        final Metric metric = metric(Map.of("measure", Metric.DURATION, "fromOperation", CREATE,
            "toOperation", SUBMIT, "aggregation", Metric.MEAN, "breakdownBy", Metric.BY_SCHEMA));

        assertNull(new Measurements(sessionless, metric).measure().get(0).breakdownKey());
    }

    @Test
    void countsAPartlessSubjectWithNoCreationDate()
    {
        final Resource undated = Mockito.mock(Resource.class);
        Mockito.when(undated.getPath()).thenReturn("/Submissions/undated");
        Mockito.when(undated.getChildren()).thenReturn(List.of());
        Mockito.when(undated.getValueMap())
            .thenReturn(new org.apache.sling.api.wrappers.ValueMapDecorator(new HashMap<>()));
        final ResourceResolver finding =
            new ResourceResolverWrapper(this.context.resourceResolver())
            {
                @Override
                public Iterator<Resource> findResources(final String query, final String language)
                {
                    return List.of(undated).iterator();
                }
            };
        final Metric metric = metric(Map.of("measure", Metric.PART_COUNT, "partType", "sub/Answer",
            "aggregation", Metric.MEDIAN));

        final List<Measurement> measured = new Measurements(finding, metric).measure();

        assertEquals(1, measured.size());
        assertEquals(0.0, measured.get(0).value());
        assertNotNull(measured.get(0).started());
    }

    @Test
    void escapesAQuoteInAnOperationName()
    {
        submission(ONE, null);
        action("a1", "it's", null, REQUESTER, ONE, days(0));
        action("a2", SUBMIT, null, REQUESTER, ONE, days(1));

        assertEquals(1, measure(duration("it's", SUBMIT, Metric.MEAN)).size());
    }

    // --- fixtures -------------------------------------------------------------------------------

    private List<Measurement> measure(final Metric metric)
    {
        return new Measurements(answering(this.context.resourceResolver()), metric).measure();
    }

    private Metric duration(final String from, final String to, final String aggregation)
    {
        return metric(Map.of("measure", Metric.DURATION, "fromOperation", from, "toOperation", to,
            "aggregation", aggregation));
    }

    private Metric metric(final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put(TYPE, Metric.RESOURCE_TYPE);
        all.put("label", "A metric");
        all.putIfAbsent("subjectType", SUBMISSION);
        final Metric metric = this.context.create()
            .resource("/Statistics/m" + all.hashCode(), all).adaptTo(Metric.class);
        assertNotNull(metric);
        return metric;
    }

    private void modify(final Resource resource, final String property, final Object value)
    {
        try {
            resource.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class).put(property, value);
            this.context.resourceResolver().commit();
        } catch (final PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private Calendar days(final int offset)
    {
        final Calendar when = (Calendar) this.day1.clone();
        when.add(Calendar.DAY_OF_YEAR, offset);
        return when;
    }

    private void schema(final String name)
    {
        this.context.create().resource("/Schemas/" + name, Map.of(TYPE, "sch/Schema"));
        this.context.create().resource("/Schemas/" + name + "/v1", Map.of(TYPE, "sch/SchemaVersion"));
    }

    /** A submission, optionally pointed at a schema version by a real reference. */
    private Resource submission(final String name, final String schemaName)
    {
        final Resource created = this.context.create().resource("/Submissions/" + name,
            Map.of(TYPE, SUBMISSION, "title", name));
        if (schemaName != null) {
            try {
                final Node version = this.context.resourceResolver()
                    .getResource("/Schemas/" + schemaName + "/v1").adaptTo(Node.class);
                created.adaptTo(Node.class).setProperty("schemaVersion", version);
                this.context.resourceResolver().commit();
            } catch (final RepositoryException | PersistenceException e) {
                throw new IllegalStateException(e);
            }
        }
        return created;
    }

    /** One recorded action about one submission, filed the way the history store files them. */
    private Resource action(final String name, final String operation, final String outcome,
        final String actor, final String subject, final Calendar when)
    {
        final Map<String, Object> properties = new HashMap<>(Map.of(TYPE, "hist/Action",
            "operation", operation, "actor", actor, "occurredAt", when));
        if (outcome != null) {
            properties.put("outcome", outcome);
        }
        final Resource created =
            this.context.create().resource("/History/aa/bb/cc/" + name, properties);
        this.context.create().resource(created.getPath() + "/" + subject, Map.of(TYPE, "hist/Entry",
            "subject", subject, "subjectPath", "/Submissions/" + subject, "subjectType", SUBMISSION));
        return created;
    }

    /**
     * A resolver that answers the queries this class builds by reading the created content: the mock
     * repository has no index, so the statement is matched rather than executed.
     */
    private ResourceResolver answering(final ResourceResolver delegate)
    {
        return new ResourceResolverWrapper(delegate)
        {
            @Override
            public Iterator<Resource> findResources(final String query, final String language)
            {
                final List<Resource> found = new ArrayList<>();
                if (query.contains("hist:Action")) {
                    final Resource history = delegate.getResource("/History/aa/bb/cc");
                    if (history != null) {
                        history.getChildren().forEach(action -> {
                            if (matches(action, query)) {
                                found.add(action);
                            }
                        });
                    }
                } else {
                    final Resource submissions = delegate.getResource("/Submissions");
                    if (submissions != null) {
                        submissions.getChildren().forEach(found::add);
                    }
                }
                return found.iterator();
            }
        };
    }

    /** Whether one action satisfies the operation and outcome the statement asks for. */
    private static boolean matches(final Resource action, final String query)
    {
        final String operation = action.getValueMap().get("operation", String.class);
        if (operation == null
            || !query.contains("[operation] = '" + operation.replace("'", "''") + "'")) {
            return false;
        }
        if (!query.contains("[outcome] = '")) {
            return true;
        }
        final String outcome = action.getValueMap().get("outcome", String.class);
        return outcome != null && query.contains("[outcome] = '" + outcome + "'");
    }
}
