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
import java.util.concurrent.TimeUnit;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityHomepage;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.errortracking.models.Acknowledgement;
import io.uhndata.iap.errortracking.models.LoggedError;
import io.uhndata.iap.errortracking.models.LoggedErrorsHomepage;
import io.uhndata.iap.errortracking.models.LoggedFailure;
import io.uhndata.iap.errortracking.models.LoggedProblem;
import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.utils.DateUtils;

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
    /**
     * A moment inside any plausible recency window. Most of these fixtures are about what the report says rather
     * than about when, but its level is a judgement about time, so "seen a moment ago" is the ordinary case here.
     */
    private static final long RECENTLY = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1);

    /** A moment outside the default window: a fault this instance is no longer getting wrong. */
    private static final long A_WHILE_AGO = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(3);

    private final SlingContext context = new SlingContext();

    private LoggedErrorsStatusReporter reporter;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        this.context.addModelsForClasses(Content.class, EntityHomepage.class, LoggedError.class,
            LoggedFailure.class, LoggedProblem.class, LoggedErrorsHomepage.class, Acknowledgement.class);
        this.context.create().resource("/LoggedErrors",
            "sling:resourceType", LoggedErrorsHomepage.RESOURCE_TYPE);
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
    void saysNothingWorthMentioningWhenNothingWasRecorded()
    {
        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.DEBUG, report.getStatus());
        assertEquals("No errors are logged", report.getName());
    }

    @Test
    void reportsAMissingContainerAsAProblemOfItsOwn() throws PersistenceException
    {
        // Errors are being dropped on the floor, which is worth knowing before the failure that needed recording
        this.context.resourceResolver().delete(this.context.resourceResolver().getResource("/LoggedErrors"));

        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.ERROR, report.getStatus());
        assertEquals("*ERROR*: Errors cannot be logged", report.getName());
    }

    @Test
    void reportsAContainerOfTheWrongTypeTheSameWay() throws PersistenceException
    {
        this.context.resourceResolver().delete(this.context.resourceResolver().getResource("/LoggedErrors"));
        this.context.create().resource("/LoggedErrors", "sling:resourceType", "data/Content");

        assertEquals("*ERROR*: Errors cannot be logged", this.reporter.report(false).getName());
    }

    @Test
    void countsWhatNeedsAttention()
    {
        thrown("first", 3, RECENTLY, null);
        thrown("second", 1, RECENTLY + 1000, null);

        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.ERROR, report.getStatus());
        assertEquals("There are 2 errors logged, 4 occurrences in total", report.getName());
    }

    @Test
    void leavesTheOccurrenceCountOutWhenEverythingHappenedOnce()
    {
        thrown("first", 1, RECENTLY, null);
        thrown("second", 1, RECENTLY + 1000, null);

        assertEquals("There are 2 errors logged", this.reporter.report(false).getName());
    }

    @Test
    void countsOneErrorInTheSingular()
    {
        thrown("only", 1, RECENTLY, null);

        assertEquals("There is 1 error logged", this.reporter.report(false).getName());
    }

    @Test
    void staysAWarningForAFaultThatIsNotHappeningAnyMore()
    {
        // Nothing recorded here is ever deleted, so a fault from last week that nobody dealt with must not keep the
        // report red for the rest of the instance's life — and must not page whoever is monitoring it
        thrown("stale", 2, A_WHILE_AGO, null);

        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.WARNING, report.getStatus());
        assertEquals("There is 1 error logged, 2 occurrences in total, no failure seen in the last 60 minutes",
            report.getName());
    }

    @Test
    void turnsRedForAFaultThatIsStillHappening()
    {
        thrown("stale", 1, A_WHILE_AGO, null);
        thrown("live", 1, RECENTLY, null);

        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.ERROR, report.getStatus());
        // Which of them made it red: the rest are the reader's to work through at their leisure
        assertEquals("There are 2 errors logged, 1 still happening", report.getName());
    }

    @Test
    void doesNotTurnRedForSomethingItMerelyFoundWrong()
    {
        // A condition naming a comparator that does not exist is somebody's to correct. However often it is hit, it
        // says nothing about whether this instance is well
        problem("misauthored", "unknown comparator", RECENTLY);

        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.WARNING, report.getStatus());
        assertEquals("There is 1 error logged, no failure seen in the last 60 minutes", report.getName());
    }

    @Test
    void turnsRedForAProblemWhereTheDeploymentSaysDefinitionsMustBeRight()
    {
        configure(60, true);
        problem("misauthored", "unknown comparator", RECENTLY);

        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.ERROR, report.getStatus());
        // And then the headline is about everything, since nothing is being left out of the judgement
        assertEquals("There is 1 error logged", report.getName());
    }

    @Test
    void speaksOfEverythingWhereNothingIsLeftOutOfTheJudgement()
    {
        // With problems counted too, the headline can say plainly that nothing has been seen. It cannot when they
        // are not: a problem hit a minute ago is not ongoing, and "none seen" would be a lie about it
        configure(60, true);
        problem("misauthored", "unknown comparator", A_WHILE_AGO);

        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.WARNING, report.getStatus());
        assertEquals("There is 1 error logged, none seen in the last 60 minutes", report.getName());
    }

    @Test
    void measuresBeingRecentAgainstTheConfiguredWindow()
    {
        // The same error and the same report at two settings: nothing about the fault itself decides this
        thrown("theOnlyOne", 1, A_WHILE_AGO, null);

        configure(5, false);
        assertEquals(StatusReport.Status.WARNING, this.reporter.report(false).getStatus());

        configure((int) TimeUnit.DAYS.toMinutes(1), false);
        assertEquals(StatusReport.Status.ERROR, this.reporter.report(false).getStatus());
    }

    @Test
    void treatsAWindowThatCannotSayWhatIsHappeningNowAsTheDefault()
    {
        // Zero silences every ERROR this reporter can raise, which is not what configuring a quieter report means
        configure(0, false);
        thrown("live", 1, RECENTLY, null);

        assertEquals(StatusReport.Status.ERROR, this.reporter.report(false).getStatus());
    }

    @Test
    void quotesWhatBrokeAndWhatItWasWorkingOn()
    {
        thrown("first", 3, RECENTLY, null);

        final String body = this.reporter.report(false).getText();

        assertTrue(body.contains("| Occurrences | Last seen | Component | Operation | Failure |"));
        assertTrue(body.contains("`io.uhndata.iap.Something`"));
        assertTrue(body.contains("`doIt`"));
        assertTrue(body.contains("**3 occurrences**"));
        assertTrue(body.contains("Affected at least 1 subject, most recently:"));
        assertTrue(body.contains("`/Submissions/1`"));
        assertTrue(body.contains("Messages seen: `boom`"));
        assertTrue(body.contains("java.lang.IllegalStateException: boom"));
        assertTrue(body.contains("Context of the last occurrence:"));
    }

    @Test
    void alwaysSaysWhenSomethingHappened()
    {
        // A one-off quoted with no date at all is precisely the case a reader most wants dated
        thrown("once", 1, RECENTLY, null);

        final String body = this.reporter.report(false).getText();

        assertTrue(body.contains("**1 occurrence**, first seen "));
        // In the platform's own format, milliseconds included and a zero offset written out rather than shortened
        // to Z, so that a date in a report reads the same as the same date in a serialized resource
        assertTrue(body.contains("last seen " + DateUtils.toString(at(RECENTLY))), body);
        assertTrue(DateUtils.toString(at(RECENTLY)).matches(".*\\.\\d{3}[+-]\\d{2}:\\d{2}"));
    }

    @Test
    void saysSomethingRatherThanTheWordNullAboutAnAbsurdDate()
    {
        // A date no plausible occurrence carries, from a record written by something with a broken clock
        final Calendar absurd = Calendar.getInstance();
        absurd.set(Calendar.YEAR, 999999999);
        this.context.create().resource("/LoggedErrors/undatable", Map.of(
            "sling:resourceType", LoggedFailure.RESOURCE_TYPE,
            "sling:resourceSuperType", LoggedError.RESOURCE_TYPE,
            "occurrences", 1L,
            "lastOccurrence", absurd));

        assertFalse(this.reporter.report(false).getText().contains("null"));
    }

    @Test
    void describesAProblemWithoutPretendingItHasATrace()
    {
        problem("broken", "unknown comparator", RECENTLY);

        final String body = this.reporter.report(false).getText();

        assertTrue(body.contains("What is wrong: `unknown comparator`"));
        assertFalse(body.contains("```\njava."));
    }

    @Test
    void hidesEverythingThatQuotesContentFromAReaderWhoIsNotLoggedIn()
    {
        thrown("first", 3, RECENTLY, null);

        final StatusReport report = this.reporter.report(true);

        final String body = report.getText();
        // What broke is the instance's own code and is safe to show; what it was working on is not
        assertTrue(body.contains("`io.uhndata.iap.Something`"));
        assertTrue(body.contains("`doIt`"));
        assertFalse(body.contains("/Submissions/1"));
        assertFalse(body.contains("boom"));
        assertTrue(body.contains("hidden while not logged in"));
    }

    @Test
    void anInstanceWhereEverythingHasBeenDealtWithIsNotReportedAsBroken()
    {
        // Nothing is ever deleted, so without this the first error an instance ever hits would leave it red forever
        thrown("first", 3, RECENTLY, "known-issue");

        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.INFO, report.getStatus());
        assertEquals("The one logged error has been acknowledged", report.getName());
        assertTrue(report.getText().contains("_known-issue_"));
    }

    @Test
    void repeatsWhyAnErrorWasSetAside()
    {
        final String name = thrown("handled", 1, RECENTLY, "known-issue");
        this.context.resourceResolver().getResource("/LoggedErrors/" + name + "/decision1")
            .adaptTo(ModifiableValueMap.class).put("note", "waiting on the partner");

        assertTrue(this.reporter.report(false).getText().contains("waiting on the partner"));
    }

    @Test
    void countsSeveralAcknowledgedErrorsTogether()
    {
        thrown("first", 1, RECENTLY, "known-issue");
        thrown("second", 1, RECENTLY + 1000, "wont-fix");

        assertEquals("All 2 logged errors have been acknowledged", this.reporter.report(false).getName());
    }

    @Test
    void saysHowMuchHasAlreadyBeenDealtWith()
    {
        thrown("needsWork", 1, RECENTLY, null);
        thrown("handled", 1, RECENTLY + 1000, "known-issue");

        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.ERROR, report.getStatus());
        assertEquals("There is 1 error logged, and 1 already acknowledged", report.getName());
        assertTrue(report.getText().contains("### Already acknowledged"));
        // Nothing is silenced invisibly: the report names what silenced it
        assertTrue(report.getText().contains("_known-issue_"));
    }

    @Test
    void tellsNobodyWhichErrorsWereSilencedWhenNotLoggedIn()
    {
        thrown("handled", 1, RECENTLY + 1000, "known-issue");

        assertEquals("", this.reporter.report(true).getText());
    }

    @Test
    void describesOnlySoManyErrorsInFull()
    {
        for (int i = 0; i < 12; i++) {
            thrown("error" + i, 1, RECENTLY + i, null);
        }

        final String body = this.reporter.report(false).getText();

        assertTrue(body.contains("_...and 2 more._"));
    }

    @Test
    void listsOnlySoManyErrorsInTheTable()
    {
        for (int i = 0; i < 30; i++) {
            thrown("error" + i, 1, RECENTLY + i, null);
        }

        assertTrue(this.reporter.report(true).getText().contains("_...and 5 more._"));
    }

    @Test
    void quotesOnlyAFewOfTheSubjects()
    {
        final String name = thrown("many", 1, RECENTLY, null);
        this.context.resourceResolver().getResource("/LoggedErrors/" + name)
            .adaptTo(ModifiableValueMap.class)
            .put("subjects", new String[] {"/one", "/two", "/three", "/four", "/five"});

        final String body = this.reporter.report(false).getText();

        assertTrue(body.contains("Affected at least 5 subjects, most recently:"));
        assertTrue(body.contains("`/three`"));
        assertFalse(body.contains("`/four`"));
        assertTrue(body.contains("- _...and more._"));
    }

    @Test
    void listsOnlySoManyAcknowledgedErrors()
    {
        for (int i = 0; i < 12; i++) {
            thrown("handled" + i, 1, RECENTLY + i, "wont-fix");
        }

        assertTrue(this.reporter.report(false).getText().contains("_...and 2 more._"));
    }

    @Test
    void saysWhatItDoesNotKnowRatherThanNothing()
    {
        // A record written before the component and the operation were established still has to read sensibly
        this.context.create().resource("/LoggedErrors/bare", Map.of(
            "sling:resourceType", "err/LoggedFailure",
            "sling:resourceSuperType", "err/LoggedError",
            "computedTags", new String[] {"acknowledged"},
            "occurrences", 1L,
            "lastOccurrence", at(1000)));

        final String body = this.reporter.report(false).getText();

        // An em dash rather than an empty cell, and no claim about a decision nobody recorded
        assertTrue(body.contains("—"));
        assertFalse(body.contains("_null_"));
    }

    // ---------------------------------------------------------------- what could not be recorded at all

    @Test
    void saysWhenFaultsArrivedFasterThanTheyCouldBeRecorded() throws ReflectiveOperationException
    {
        // Nothing in the repository and yet something lost: the overflow is the only trace left, so a report that
        // stayed quiet about it would be exactly the silent failure this module exists to prevent
        droppedSoFar(7);

        final StatusReport report = this.reporter.report(false);

        assertEquals(StatusReport.Status.WARNING, report.getStatus());
        assertEquals("*WARNING*: 7 errors could not be recorded", report.getName());
        assertTrue(report.getText().contains("**7 errors could not be recorded**"));
    }

    @Test
    void countsTheOneThatCouldNotBeRecordedInTheSingular() throws ReflectiveOperationException
    {
        droppedSoFar(1);

        final StatusReport report = this.reporter.report(false);

        assertEquals("*WARNING*: 1 error could not be recorded", report.getName());
        assertTrue(report.getText().contains("**1 error could not be recorded**"));
    }

    @Test
    void countsWhatCouldNotBeRecordedAlongsideWhatWas() throws ReflectiveOperationException
    {
        thrown("first", 1, RECENTLY, null);
        droppedSoFar(3);

        final StatusReport report = this.reporter.report(false);

        assertEquals("There is 1 error logged, and 3 that could not be recorded at all", report.getName());
        assertTrue(report.getText().contains("**3 errors could not be recorded**"));
    }

    @Test
    void nothingAcknowledgesWhatWasNeverRecorded() throws ReflectiveOperationException
    {
        // Acknowledging every error there is cannot silence an overflow: nobody ever saw what was dropped
        thrown("handled", 1, RECENTLY, "known-issue");
        droppedSoFar(2);

        final StatusReport report = this.reporter.report(false);

        // Loud, but not red: what an overflow says is that faults once arrived faster than they could be written,
        // and the count is cumulative, so red here would mean red until this instance is restarted
        assertEquals(StatusReport.Status.WARNING, report.getStatus());
        assertEquals("Nothing logged needs attention, and 2 could not be recorded at all, and 1 already acknowledged",
            report.getName());
    }

    @Test
    void saysHowMuchWasLostEvenToAReaderWhoIsNotLoggedIn() throws ReflectiveOperationException
    {
        // A count of what was dropped says nothing about what the instance was working on
        thrown("first", 1, RECENTLY, null);
        droppedSoFar(4);

        assertTrue(this.reporter.report(true).getText().contains("**4 errors could not be recorded**"));
    }

    @Test
    void reportsWhatIsRecordedWithNoRecorderToAskAtAll()
    {
        // The reference is left unset here, standing in for the window between the report starting and the recorder
        // being injected: the errors already in the repository are still worth reporting
        thrown("first", 1, RECENTLY, null);

        assertEquals("There is 1 error logged", this.reporter.report(false).getName());
    }

    @Test
    void reportsItsOwnFailureToRead() throws ReflectiveOperationException
    {
        final LoggedErrorsStatusReporter broken = new LoggedErrorsStatusReporter();
        TestResolvers.inject(broken, null);

        final StatusReport report = broken.report(false);

        assertEquals("*ERROR*: Could not report logged errors", report.getName());
        assertEquals(StatusReport.Status.ERROR, report.getStatus());
        assertTrue(report.getText().contains("No such service user"));
    }

    @Test
    void doesNotLeakWhyItCouldNotReadToAReaderWhoIsNotLoggedIn() throws ReflectiveOperationException
    {
        // A repository failure routinely quotes a path, and /system/status answers without authentication
        final LoggedErrorsStatusReporter broken = new LoggedErrorsStatusReporter();
        TestResolvers.inject(broken, null);

        final StatusReport report = broken.report(true);

        assertFalse(report.getText().contains("No such service user"));
        assertTrue(report.getText().contains("hidden while not logged in"));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Configures the reporter, the way the framework would.
     *
     * @param window how long a failure counts as still happening, in minutes
     * @param problemsAreUrgent whether something merely found wrong can make the report an error
     */
    private void configure(final int window, final boolean problemsAreUrgent)
    {
        this.reporter.activate(new ErrorReportConfiguration()
        {
            @Override
            public Class<ErrorReportConfiguration> annotationType()
            {
                return ErrorReportConfiguration.class;
            }

            @Override
            public int recentFailureWindow()
            {
                return window;
            }

            @Override
            public boolean problemsAreUrgent()
            {
                return problemsAreUrgent;
            }
        });
    }

    /**
     * Gives the reporter a recording service that has had to drop that many faults.
     *
     * @param dropped how many recordings could not be kept up with
     * @throws ReflectiveOperationException if the component's shape has changed
     */
    private void droppedSoFar(final long dropped) throws ReflectiveOperationException
    {
        TestResolvers.set(this.reporter, "recorder", new ErrorLoggerService()
        {
            @Override
            public void logError(final Throwable error)
            {
                // Nothing is recorded through this one
            }

            @Override
            public void logError(final Throwable error, final ErrorContext context)
            {
                // Nothing is recorded through this one
            }

            @Override
            public void logProblem(final String problem, final ErrorContext context)
            {
                // Nothing is recorded through this one
            }

            @Override
            public long getDroppedCount()
            {
                return dropped;
            }
        });
    }

    /**
     * Records an error something was thrown for.
     *
     * @param name the fingerprint naming it
     * @param occurrences how often it happened
     * @param lastSeen when it was last seen
     * @param resolution what was decided about it, {@code null} when nobody has
     * @return the name, for chaining
     */
    private String thrown(final String name, final long occurrences, final long lastSeen, final String resolution)
    {
        final Map<String, Object> properties = new HashMap<>(Map.of(
            "sling:resourceType", LoggedFailure.RESOURCE_TYPE,
            "sling:resourceSuperType", LoggedError.RESOURCE_TYPE,
            "type", "java.lang.IllegalStateException",
            "stackTrace", "java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.java:1)",
            "messages", new String[] {"boom"},
            "occurrences", occurrences,
            "lastOccurrence", at(lastSeen),
            "component", "io.uhndata.iap.Something",
            "operation", "doIt"));
        properties.put("subjects", new String[] {"/Submissions/1"});
        properties.put("lastContext", "attempt: 3");
        if (resolution != null) {
            properties.put("computedTags", new String[] {"acknowledged", resolution});
        }
        this.context.create().resource("/LoggedErrors/" + name, properties);
        if (resolution != null) {
            this.context.create().resource("/LoggedErrors/" + name + "/decision1", Map.of(
                "sling:resourceType", Acknowledgement.RESOURCE_TYPE,
                "resolution", resolution,
                "acknowledgedOccurrences", occurrences));
        }
        return name;
    }

    /**
     * Records something found wrong that nothing was thrown for.
     *
     * @param name the fingerprint naming it
     * @param what what is wrong
     * @param lastSeen when it was last seen
     */
    private void problem(final String name, final String what, final long lastSeen)
    {
        this.context.create().resource("/LoggedErrors/" + name, Map.of(
            "sling:resourceType", LoggedProblem.RESOURCE_TYPE,
            "sling:resourceSuperType", LoggedError.RESOURCE_TYPE,
            "problem", what,
            "occurrences", 1L,
            "lastOccurrence", at(lastSeen),
            "component", "io.uhndata.iap.Conditions",
            "operation", "evaluate"));
    }

    /**
     * A calendar for a moment in time.
     *
     * @param moment milliseconds since the epoch
     * @return the calendar
     */
    private static Calendar at(final long moment)
    {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(moment);
        return calendar;
    }
}
