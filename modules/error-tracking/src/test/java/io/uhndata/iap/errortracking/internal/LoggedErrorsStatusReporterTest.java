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
package io.uhndata.iap.errortracking.internal;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.status.spi.StatusReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LoggedErrorsStatusReporter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LoggedErrorsStatusReporterTest
{
    private final SlingContext context = new SlingContext();

    private LoggedErrorsStatusReporter reporter;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        this.context.create().resource(ErrorLoggerService.LOGGED_ERRORS_PATH,
            "sling:resourceType", "err/LoggedErrorsHomepage");
        this.reporter = new LoggedErrorsStatusReporter();
        TestResolvers.inject(this.reporter, this.context.resourceResolver());
    }

    @Test
    void describesItself()
    {
        assertEquals("Logged errors", this.reporter.getName());
        assertEquals(Set.of("problems", "errors"), this.reporter.getTags());
    }

    @Test
    void reportsNothingWorthMentioningWhenNoErrorsWereRecorded()
    {
        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.DEBUG, report.getStatus());
        assertEquals("No errors are logged", report.getName());
    }

    @Test
    void quotesTheRecordedStackTraces()
    {
        record("first", "boom", 1, 1000);

        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.ERROR, report.getStatus());
        assertEquals("There is 1 error logged:", report.getName());
        assertTrue(report.getText().contains("boom"));
        assertTrue(report.getText().contains("```"));
    }

    @Test
    void countsTheErrorsAndTheirOccurrences()
    {
        record("first", "boom", 3, 1000);
        record("second", "bang", 1, 2000);

        // Two distinct errors, but the first one happened three times
        assertEquals("There are 2 errors logged, 4 occurrences in total:", this.reporter.report(false).getName());
    }

    @Test
    void leavesTheOccurrenceCountOutWhenEveryErrorHappenedOnce()
    {
        record("first", "boom", 1, 1000);
        record("second", "bang", 1, 2000);

        assertEquals("There are 2 errors logged:", this.reporter.report(false).getName());
    }

    @Test
    void quotesHowOftenARepeatedErrorHappened()
    {
        record("repeated", "boom", 7, 1000);

        final String body = this.reporter.report(false).getText();

        assertTrue(body.contains("7 occurrences, last seen 1970-01-01T00:00:01Z"));
    }

    @Test
    void saysNothingAboutOccurrencesOfAnErrorSeenOnce()
    {
        record("once", "boom", 1, 1000);

        assertFalse(this.reporter.report(false).getText().contains("occurrences"));
    }

    @Test
    void treatsAnUncountedErrorAsOneOccurrence()
    {
        record("uncounted", "boom", null, 1000);

        assertEquals("There is 1 error logged:", this.reporter.report(false).getName());
    }

    @Test
    void hidesTheContentFromAnUnprivilegedReport()
    {
        record("first", "boom", 1, 1000);
        record("second", "bang", 1, 2000);

        final StatusReport report = this.reporter.report(true);

        // A stack trace quotes whatever the failing code was working on, so only the counts may be shown
        assertEquals(StatusReport.Status.ERROR, report.getStatus());
        assertEquals("There are 2 errors logged", report.getName());
        assertFalse(report.getText().contains("boom"));
    }

    @Test
    void ordersTheMostRecentlySeenErrorsFirst()
    {
        record("older", "older failure", 1, 1000);
        record("newer", "newer failure", 1, 2000);

        final String body = this.reporter.report(false).getText();

        assertTrue(body.indexOf("newer failure") < body.indexOf("older failure"));
    }

    @Test
    void fallsBackToTheFirstSeenDateWhenAnErrorWasNeverCounted()
    {
        recordCreatedOnly("dated", "dated failure", 2000);
        record("older", "older failure", 1, 1000);

        final String body = this.reporter.report(false).getText();

        assertTrue(body.indexOf("dated failure") < body.indexOf("older failure"));
    }

    @Test
    void errorsWithNoDateAtAllSortLast()
    {
        recordCreatedOnly("undated", "undated failure", null);
        record("dated", "dated failure", 1, 1000);

        final String body = this.reporter.report(false).getText();

        assertTrue(body.indexOf("dated failure") < body.indexOf("undated failure"));
    }

    @Test
    void quotesOnlyTheFirstFewErrors()
    {
        for (int i = 0; i < 12; i++) {
            record("error" + i, "failure " + i, 1, 1000 + i);
        }

        final StatusReport report = this.reporter.report(false);

        // Nothing is discarded from the repository, but the report only quotes the 10 most recently seen
        assertTrue(report.getText().contains("failure 11"));
        assertTrue(report.getText().contains("failure 2"));
        assertFalse(report.getText().contains("failure 1\n"));
        assertTrue(report.getText().contains("...and 2 more."));
    }

    @Test
    void ignoresChildrenThatAreNotRecordedErrors()
    {
        this.context.create().resource(ErrorLoggerService.LOGGED_ERRORS_PATH + "/notAnError",
            "sling:resourceType", "iap/Content");

        assertEquals(StatusReport.Status.DEBUG, this.reporter.report(false).getStatus());
    }

    @Test
    void reportsAMissingContainerAsAProblemOfItsOwn() throws PersistenceException
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        resolver.delete(resolver.getResource(ErrorLoggerService.LOGGED_ERRORS_PATH));
        resolver.commit();

        final StatusReport report = this.reporter.report(false);

        // Without the container no error can be recorded at all, which is worse than having errors to report
        assertEquals(StatusReport.Status.ERROR, report.getStatus());
        assertEquals("*ERROR*: Errors cannot be logged", report.getName());
        assertTrue(report.getText().contains(ErrorLoggerService.LOGGED_ERRORS_PATH));
    }

    @Test
    void reportsItsOwnFailureToRead() throws ReflectiveOperationException
    {
        TestResolvers.inject(this.reporter, null);

        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.ERROR, report.getStatus());
        assertEquals("*ERROR*: Could not report logged errors", report.getName());
    }

    /**
     * Records one error the way the logger would.
     *
     * @param name the node name
     * @param trace the stack trace to store
     * @param occurrences how many times the error happened, {@code null} to leave the count out
     * @param lastSeen when the error was last seen, in milliseconds since the epoch
     */
    private void record(final String name, final String trace, final Integer occurrences, final int lastSeen)
    {
        final Map<String, Object> properties = properties(trace);
        properties.put("lastOccurrence", moment(lastSeen));
        if (occurrences != null) {
            properties.put("occurrences", (long) occurrences);
        }
        this.context.create().resource(ErrorLoggerService.LOGGED_ERRORS_PATH + "/" + name, properties);
    }

    /**
     * Records one error the way a session that could not count it would have, i.e. with only the date
     * {@code mix:created} autocreates.
     *
     * @param name the node name
     * @param trace the stack trace to store
     * @param created when the error was first seen, {@code null} to leave every date out
     */
    private void recordCreatedOnly(final String name, final String trace, final Integer created)
    {
        final Map<String, Object> properties = properties(trace);
        if (created != null) {
            properties.put("jcr:created", moment(created));
        }
        this.context.create().resource(ErrorLoggerService.LOGGED_ERRORS_PATH + "/" + name, properties);
    }

    private Map<String, Object> properties(final String trace)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put("jcr:primaryType", "err:LoggedError");
        properties.put("type", "java.lang.IllegalStateException");
        properties.put("stackTrace", trace);
        return properties;
    }

    private Calendar moment(final int millis)
    {
        final Calendar moment = Calendar.getInstance();
        moment.setTimeInMillis(millis);
        return moment;
    }
}
