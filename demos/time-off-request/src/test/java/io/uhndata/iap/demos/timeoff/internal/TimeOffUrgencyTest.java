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
package io.uhndata.iap.demos.timeoff.internal;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.models.Taggable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TimeOffUrgency}: which requests count as about to start, and which are judged at all.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TimeOffUrgencyTest
{
    private static final String REQUEST_PATH = "/Submissions/aRequest";

    /** A Tuesday, so that tomorrow is a working day too. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private TimeOffRequests requests;

    @BeforeEach
    void setUp()
    {
        PropertyTags.installIn(this.context);
        this.requests = TimeOffRequests.installIn(this.context);
    }

    @Test
    void flagsARequestStartingTomorrow() throws PersistenceException
    {
        assertTrue(flaggedWhenStarting("2026-09-16"));
    }

    @Test
    void flagsARequestStartingToday() throws PersistenceException
    {
        assertTrue(flaggedWhenStarting("2026-09-15"));
    }

    @Test
    void flagsARequestWhoseTimeOffHasAlreadyBegun() throws PersistenceException
    {
        assertTrue(flaggedWhenStarting("2026-09-01"));
    }

    @Test
    void leavesARequestStartingTheDayAfterTomorrow() throws PersistenceException
    {
        assertFalse(flaggedWhenStarting("2026-09-17"));
    }

    @Test
    void readsADateThatCameWithATimeOfDay() throws PersistenceException
    {
        assertTrue(flaggedWhenStarting("2026-09-16T09:30:00.000-04:00"));
    }

    @Test
    void leavesARequestWhoseStartDateIsBlank() throws PersistenceException
    {
        // Clearing a date leaves the answer node behind, holding nothing
        assertFalse(flaggedWhenStarting("   "));
    }

    @Test
    void leavesARequestWhoseStartDateCannotBeRead() throws PersistenceException
    {
        assertFalse(flaggedWhenStarting("next Tuesday"));
    }

    @Test
    void leavesARequestThatHasNotSaidWhenItStarts() throws PersistenceException
    {
        TimeOffUrgency.mark(waitingRequest(), TODAY);

        assertFalse(this.requests.tagsOf(REQUEST_PATH).contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void takesTheFlagOffARequestThatNoLongerStartsSoon() throws PersistenceException
    {
        final Resource request = this.requests.request(REQUEST_PATH,
            Map.of("tags", new String[] {TimeOffUrgency.SUBMITTED_TAG, TimeOffUrgency.URGENT_TAG}));
        this.requests.answer(request, TimeOffQuestions.START_DATE_QUESTION, "2026-10-30");

        TimeOffUrgency.mark(request, TODAY);

        assertFalse(this.requests.tagsOf(REQUEST_PATH).contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void leavesADraftAlone() throws PersistenceException
    {
        assertLeftAlone("draft");
    }

    @Test
    void leavesAnApprovedRequestAlone() throws PersistenceException
    {
        assertLeftAlone("approved");
    }

    @Test
    void leavesARejectedRequestAlone() throws PersistenceException
    {
        assertLeftAlone("rejected");
    }

    @Test
    void leavesAnExpiredRequestAlone() throws PersistenceException
    {
        assertLeftAlone("expired");
    }

    @Test
    void doesNothingToSomethingThatCannotBeReadAsARequest() throws PersistenceException
    {
        // Mocked, because sling-mock adapts a resource of any type to Submission. A mock adapts to nothing,
        // which is the case the guard is for
        final Resource unreadable = Mockito.mock(Resource.class);
        Mockito.when(unreadable.adaptTo(Submission.class)).thenReturn(null);

        TimeOffUrgency.mark(unreadable, TODAY);

        Mockito.verify(unreadable, Mockito.never()).adaptTo(Taggable.class);
    }

    /** Whether a waiting request whose start date answer stores this string is flagged once judged today. */
    private boolean flaggedWhenStarting(final String startDate) throws PersistenceException
    {
        final Resource request = waitingRequest();
        this.requests.answer(request, TimeOffQuestions.START_DATE_QUESTION, startDate);

        TimeOffUrgency.mark(request, TODAY);

        return this.requests.tagsOf(REQUEST_PATH).contains(TimeOffUrgency.URGENT_TAG);
    }

    /**
     * Checks that a request carrying this lifecycle tag, starting tomorrow, is not written to: it is not waiting for a
     * decision.
     */
    private void assertLeftAlone(final String lifecycleTag) throws PersistenceException
    {
        final Resource request = this.requests.request(REQUEST_PATH, Map.of("tags", new String[] {lifecycleTag}));
        this.requests.answer(request, TimeOffQuestions.START_DATE_QUESTION, "2026-09-16");
        final Taggable taggable = Objects.requireNonNull(request.adaptTo(Taggable.class));

        TimeOffUrgency.mark(request, TODAY);

        Mockito.verify(taggable, Mockito.never()).tag(Mockito.anyString(), Mockito.anyBoolean());
        Mockito.verify(taggable, Mockito.never()).untag(Mockito.anyString(), Mockito.anyBoolean());
    }

    private Resource waitingRequest()
    {
        return this.requests.request(REQUEST_PATH, Map.of("tags", new String[] {TimeOffUrgency.SUBMITTED_TAG}));
    }
}
