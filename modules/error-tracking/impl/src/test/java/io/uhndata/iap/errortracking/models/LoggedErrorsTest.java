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
package io.uhndata.iap.errortracking.models;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityHomepage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the recorded-error models.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class LoggedErrorsTest
{
    private final SlingContext context = new SlingContext();

    private LoggedErrorsHomepage home;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityHomepage.class, LoggedError.class,
            LoggedFailure.class, LoggedProblem.class, LoggedErrorsHomepage.class, Acknowledgement.class);
        this.home = this.context.create()
            .resource("/LoggedErrors", "sling:resourceType", LoggedErrorsHomepage.RESOURCE_TYPE)
            .adaptTo(LoggedErrorsHomepage.class);
    }

    @Test
    void adaptsBothKindsOfRecordingToTheSharedType()
    {
        thrown("first", 1, 1000);
        problem("second", "unknown comparator", 2000);

        final List<LoggedError> errors = this.home.getErrors();

        assertEquals(2, errors.size());
        assertTrue(errors.get(0) instanceof LoggedProblem);
        assertTrue(errors.get(1) instanceof LoggedFailure);
    }

    @Test
    void listsTheMostRecentlySeenFirst()
    {
        thrown("older", 1, 1000);
        thrown("newest", 1, 3000);
        thrown("middle", 1, 2000);

        assertEquals(List.of("newest", "middle", "older"), this.home.getErrors().stream()
            .map(LoggedError::getName).toList());
    }

    @Test
    void skipsChildrenThatAreNotRecordedErrors()
    {
        thrown("real", 1, 1000);
        this.context.create().resource("/LoggedErrors/config", "sling:resourceType", "data/Content");

        assertEquals(1, this.home.getErrors().size());
    }

    @Test
    void findsOneErrorByTheFingerprintNamingIt()
    {
        thrown("abc123", 1, 1000);

        assertNotNull(this.home.getError("abc123"));
        assertNull(this.home.getError("nosuchthing"));
    }

    @Test
    void addsUpHowMuchHasHappened()
    {
        thrown("first", 3, 1000);
        thrown("second", 4, 2000);

        assertEquals(7, this.home.getTotalOccurrences());
    }

    @Test
    void describesWhatWasThrown()
    {
        final LoggedFailure error = (LoggedFailure) this.home.getError(thrown("abc", 3, 1000));

        assertEquals("java.lang.IllegalStateException", error.getThrowableType());
        assertEquals("java.lang.IllegalStateException", error.getSummary());
        assertEquals(List.of("boom"), error.getMessages());
        assertTrue(error.getStackTrace().startsWith("java.lang.IllegalStateException"));
        assertEquals(3, error.getOccurrences());
        assertEquals(1000, error.getLastOccurrence().getTimeInMillis());
        assertEquals(List.of("/Submissions/1"), error.getSubjects());
        assertEquals(List.of("alice"), error.getActors());
        assertEquals("attempt: 3", error.getLastContext());
        assertEquals("io.uhndata.iap.Something", error.getComponent());
        assertEquals("doIt", error.getOperation());
    }

    @Test
    void describesWhatWasFoundWrong()
    {
        final LoggedProblem found = (LoggedProblem) this.home.getError(problem("abc", "unknown comparator", 1000));

        assertEquals("unknown comparator", found.getProblem());
        assertEquals("unknown comparator", found.getSummary());
    }

    @Test
    void aRecordWithNothingUsableInItStillReadsSafely()
    {
        this.context.create().resource("/LoggedErrors/bare", Map.of(
            "sling:resourceType", LoggedFailure.RESOURCE_TYPE,
            "sling:resourceSuperType", LoggedError.RESOURCE_TYPE));

        final LoggedError bare = this.home.getError("bare");

        // A record written by something that could not describe or date it sorts last rather than breaking the
        // whole listing
        assertEquals("", ((LoggedFailure) bare).getThrowableType());
        assertEquals("", ((LoggedFailure) bare).getStackTrace());
        assertEquals(List.of(), ((LoggedFailure) bare).getMessages());
        assertEquals(1, bare.getOccurrences());
        assertEquals(0, bare.getLastOccurrence().getTimeInMillis());
        assertEquals(0, bare.getFirstOccurrence().getTimeInMillis());
        assertEquals(List.of(), bare.getSubjects());
        assertEquals(List.of(), bare.getActors());
        assertNull(bare.getLastContext());
    }

    @Test
    void aProblemWithNothingUsableInItReadsSafelyToo()
    {
        this.context.create().resource("/LoggedErrors/bare", Map.of(
            "sling:resourceType", LoggedProblem.RESOURCE_TYPE,
            "sling:resourceSuperType", LoggedError.RESOURCE_TYPE));

        assertEquals("", ((LoggedProblem) this.home.getError("bare")).getProblem());
    }

    @Test
    void aProblemKeepsThePhrasesItWasReportedWith()
    {
        // Where a phrase too variable to name the fault by ends up, the same place a throwable's message does
        this.context.create().resource("/LoggedErrors/quoted", Map.of(
            "sling:resourceType", LoggedProblem.RESOURCE_TYPE,
            "sling:resourceSuperType", LoggedError.RESOURCE_TYPE,
            "problem", "unknown comparator",
            "messages", new String[] {"unknown comparator: 'sameDay'"}));

        assertEquals(List.of("unknown comparator: 'sameDay'"), this.home.getError("quoted").getMessages());
    }

    @Test
    void anErrorCountsAsOneOccurrenceEvenWhenTheCountIsNonsense()
    {
        this.context.create().resource("/LoggedErrors/odd", Map.of(
            "sling:resourceType", LoggedFailure.RESOURCE_TYPE,
            "sling:resourceSuperType", LoggedError.RESOURCE_TYPE,
            "occurrences", 0L));

        assertEquals(1, this.home.getError("odd").getOccurrences());
    }

    @Test
    void fallsBackToWhenItWasFirstSeen()
    {
        final Resource error = this.context.create().resource("/LoggedErrors/undated", Map.of(
            "sling:resourceType", LoggedFailure.RESOURCE_TYPE,
            "sling:resourceSuperType", LoggedError.RESOURCE_TYPE,
            "jcr:created", at(1500)));

        assertEquals(1500, error.adaptTo(LoggedError.class).getLastOccurrence().getTimeInMillis());
        assertEquals(1500, error.adaptTo(LoggedError.class).getFirstOccurrence().getTimeInMillis());
    }

    // ---------------------------------------------------------------- triage

    @Test
    void anErrorWithNoMarkersYetNeedsAttention()
    {
        thrown("abc", 1, 1000);

        assertFalse(this.home.getError("abc").isAcknowledged());
        assertEquals(1, this.home.getUnacknowledgedErrors().size());
        assertEquals(0, this.home.getAcknowledgedErrors().size());
    }

    @Test
    void anErrorMarkedAsNeedingAttentionNeedsAttention()
    {
        markers(thrown("abc", 1, 1000), LoggedError.UNACKNOWLEDGED);

        assertFalse(this.home.getError("abc").isAcknowledged());
        assertEquals(1, this.home.getUnacknowledgedErrors().size());
    }

    @Test
    void anErrorMarkedAsDealtWithDoesNot()
    {
        markers(thrown("abc", 1, 1000), "acknowledged", "known-issue");

        assertTrue(this.home.getError("abc").isAcknowledged());
        assertEquals(List.of("acknowledged", "known-issue"), this.home.getError("abc").getTriageMarkers());
        assertEquals(0, this.home.getUnacknowledgedErrors().size());
        assertEquals(1, this.home.getAcknowledgedErrors().size());
    }

    @Test
    void aMarkerPutThereBySomethingElseDoesNotCountAsHavingDealtWithIt()
    {
        // The computed property is the union of what every processor of that phase contributed, not only the triage
        // one. Reading "anything other than unacknowledged" as dealt with would silence the whole report the first
        // time some other processor tagged an error for reasons of its own
        markers(thrown("abc", 1, 1000), "large", "from-import");

        assertFalse(this.home.getError("abc").isAcknowledged());
        assertEquals(1, this.home.getUnacknowledgedErrors().size());
        assertEquals(0, this.home.getAcknowledgedErrors().size());
    }

    @Test
    void theContainerAnswersWhetherAnythingNeedsAttentionInOnePropertyRead()
    {
        assertFalse(this.home.hasUnacknowledgedErrors());

        this.context.resourceResolver().getResource("/LoggedErrors")
            .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
            .put("aggregatedTags", new String[] {LoggedError.UNACKNOWLEDGED});

        assertTrue(this.home.hasUnacknowledgedErrors());
    }

    @Test
    void keepsTheWholeHistoryOfWhatWasDecided()
    {
        final String name = thrown("abc", 7, 1000);
        decide(name, "first", "known-issue", 3, "fix on the way");
        decide(name, "second", "wont-fix", 7, null);

        final LoggedError error = this.home.getError(name);
        final List<Acknowledgement> decisions = error.getAcknowledgements();

        assertEquals(2, decisions.size());
        // Newest first, by how much had happened when each was taken
        assertEquals("wont-fix", decisions.get(0).getResolution());
        assertEquals("known-issue", decisions.get(1).getResolution());
        assertEquals("fix on the way", decisions.get(1).getNote());
        assertNull(decisions.get(0).getNote());
        assertEquals(7, decisions.get(0).getAcknowledgedOccurrences());
        assertEquals("wont-fix", error.getLatestAcknowledgement().getResolution());
    }

    @Test
    void anErrorNobodyDecidedAboutHasNoLatestDecision()
    {
        assertNull(this.home.getError(thrown("abc", 1, 1000)).getLatestAcknowledgement());
    }

    @Test
    void aDecisionRecordSayingNothingReadsSafely()
    {
        final String name = thrown("abc", 1, 1000);
        this.context.create().resource("/LoggedErrors/" + name + "/bare", Map.of(
            "sling:resourceType", Acknowledgement.RESOURCE_TYPE));

        final Acknowledgement decision = this.home.getError(name).getLatestAcknowledgement();

        assertEquals("", decision.getResolution());
        assertEquals(0, decision.getAcknowledgedOccurrences());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Records an error something was thrown for.
     *
     * @param name the fingerprint naming it
     * @param occurrences how often it happened
     * @param lastSeen when it was last seen
     * @return the name, for chaining
     */
    private String thrown(final String name, final long occurrences, final long lastSeen)
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
            "operation", "doIt",
            "subjects", new String[] {"/Submissions/1"}));
        properties.put("actors", new String[] {"alice"});
        properties.put("lastContext", "attempt: 3");
        this.context.create().resource("/LoggedErrors/" + name, properties);
        return name;
    }

    /**
     * Records something found wrong that nothing was thrown for.
     *
     * @param name the fingerprint naming it
     * @param what what is wrong
     * @param lastSeen when it was last seen
     * @return the name, for chaining
     */
    private String problem(final String name, final String what, final long lastSeen)
    {
        this.context.create().resource("/LoggedErrors/" + name, Map.of(
            "sling:resourceType", LoggedProblem.RESOURCE_TYPE,
            "sling:resourceSuperType", LoggedError.RESOURCE_TYPE,
            "problem", what,
            "occurrences", 1L,
            "lastOccurrence", at(lastSeen)));
        return name;
    }

    /**
     * Puts the triage markers a commit would have computed onto a recorded error.
     *
     * @param name the error to mark
     * @param computed the markers
     */
    private void markers(final String name, final String... computed)
    {
        this.context.resourceResolver().getResource("/LoggedErrors/" + name)
            .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
            .put("computedTags", computed);
    }

    /**
     * Records a decision about an error.
     *
     * @param error the error being decided about
     * @param name the name of the decision node
     * @param resolution what was decided
     * @param decidedAt how often the error had happened by then
     * @param note why, may be {@code null}
     */
    private void decide(final String error, final String name, final String resolution, final long decidedAt,
        final String note)
    {
        final Map<String, Object> properties = new HashMap<>(Map.of(
            "sling:resourceType", Acknowledgement.RESOURCE_TYPE,
            "resolution", resolution,
            "acknowledgedOccurrences", decidedAt));
        if (note != null) {
            properties.put("note", note);
        }
        this.context.create().resource("/LoggedErrors/" + error + "/" + name, properties);
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
