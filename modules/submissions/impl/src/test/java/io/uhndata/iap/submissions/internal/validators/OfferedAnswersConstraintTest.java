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

import java.util.Map;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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
