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
package io.uhndata.iap.submissions.internal.validators;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.schemas.models.Question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link OfferedAnswersConstraint}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class OfferedAnswersConstraintTest
{
    private final SlingContext context = ConstraintFixture.newContext();

    private ConstraintFixture fixture;

    @BeforeEach
    void setUp()
    {
        this.fixture = ConstraintFixture.showingEverything(this.context);
    }

    @Test
    void acceptsAnAnswerTheQuestionOffers() throws Exception
    {
        assertNull(offeringDurations().verdictOn(Map.of(), "multiple-days"));
    }

    @Test
    void refusesAnAnswerTheQuestionDoesNotOffer() throws Exception
    {
        assertEquals("\"a fortnight\" is not one of the answers \"The question\" offers.",
            offeringDurations().verdictOn(Map.of(), "a fortnight"));
    }

    // The same items the form shows and a model reading the documents is offered
    @Test
    void judgesAnAnswerAgainstTheItemsAQuestionTakesItsAnswersFrom() throws Exception
    {
        this.context.create().resource("/Durations/half", Map.of("label", "Half day"));

        assertNull(this.fixture.verdictOn(Map.of("optionsFrom", "/Durations"), "/Durations/half"));
        assertEquals("\"a fortnight\" is not one of the answers \"The question\" offers.",
            this.fixture.verdictOn(Map.of("optionsFrom", "/Durations"), "a fortnight"));
    }

    // A request that picked an item before it was withdrawn, such as a retired category, must stay savable
    @Test
    void acceptsAnItemOfTheCatalogueNoLongerOffered() throws Exception
    {
        this.context.create().resource("/Durations/half", Map.of("label", "Half day"));
        this.context.create().resource("/Durations/half/old", Map.of("label", "Morning only"));

        assertNull(this.fixture.verdictOn(Map.of("optionsFrom", "/Durations"), "/Durations/half/old"));
        assertEquals("\"/Durations/gone\" is not one of the answers \"The question\" offers.",
            this.fixture.verdictOn(Map.of("optionsFrom", "/Durations"), "/Durations/gone"));
    }

    // Every save checks every question, so an unanswered one must not read its catalogue
    @Test
    void readsNoCatalogueForAQuestionLeftUnanswered()
    {
        final Question question = Mockito.mock(Question.class);

        assertEquals(Optional.empty(), new OfferedAnswersConstraint().refusal(question, List.of()));
        Mockito.verifyNoInteractions(question);
    }

    @Test
    void leavesAQuestionOfferingNothingAnsweredFreely() throws Exception
    {
        assertNull(this.fixture.verdictOn(Map.of(), "anything at all"));
    }

    private ConstraintFixture offeringDurations()
    {
        return this.fixture.offering("half-day", "multiple-days");
    }
}
