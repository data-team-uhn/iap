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
 * Unit tests for {@link MaxAnswersValidator}: the ceiling refuses, the floor does not, and blanks get no say.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MaxAnswersValidatorTest
{
    private static final String MAX_ANSWERS = "maxAnswers";

    private final SlingContext context = ConstraintFixture.newContext();

    private ConstraintFixture fixture;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        this.fixture = ConstraintFixture.showingEverything(this.context, new MaxAnswersValidator());
    }

    @Test
    void acceptsAsManyValuesAsTheQuestionTakes() throws Exception
    {
        assertNull(this.fixture.verdictOn(Map.of(MAX_ANSWERS, 2L), "a", "b"));
    }

    @Test
    void refusesMoreValuesThanTheQuestionTakes() throws Exception
    {
        assertEquals("Too many values for \"The question\". Given: 3. Allowed: 2.",
            this.fixture.verdictOn(Map.of(MAX_ANSWERS, 2L), "a", "b", "c"));
    }

    // The pair's declared default is one value, and it must hold even where nothing was stored
    @Test
    void refusesASecondValueWhereNoMaximumWasStored() throws Exception
    {
        assertEquals("Too many values for \"The question\". Given: 2. Allowed: 1.",
            this.fixture.verdictOn(Map.of(), "a", "b"));
    }

    @Test
    void countsOnlyNonBlankValues() throws Exception
    {
        assertNull(this.fixture.verdictOn(Map.of(MAX_ANSWERS, 1L), "a", " "));
    }

    @Test
    void ignoresQuestionsTakingAnyNumber() throws Exception
    {
        assertNull(this.fixture.verdictOn(Map.of(MAX_ANSWERS, 0L), "a", "b", "c", "d"));
    }

    @Test
    void passesOverAQuestionTheSubmitterIsNotShown() throws Exception
    {
        assertNull(ConstraintFixture.hidingEverything(this.context, new MaxAnswersValidator())
            .verdictOn(Map.of(MAX_ANSWERS, 1L), "a", "b"));
    }

    @Test
    void acceptsAnUnansweredQuestion() throws Exception
    {
        assertNull(this.fixture.verdictOn(Map.of(MAX_ANSWERS, 1L)));
    }
}
