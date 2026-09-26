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
package io.uhndata.iap.submissions.internal;

import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
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
    private static final String REQUESTER = "someone";

    private static final String MAX_ANSWERS = "maxAnswers";

    // JCR-backed: a submission points at its schema version, and an answer at its question, with real REFERENCEs
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final MaxAnswersValidator validator = new MaxAnswersValidator();

    @Test
    void acceptsAsManyValuesAsTheQuestionTakes() throws Exception
    {
        assertNull(this.validator.validate(
            ConstraintFixture.submission(this.context, Map.of(MAX_ANSWERS, 2L), "a", "b"), REQUESTER));
    }

    @Test
    void refusesMoreValuesThanTheQuestionTakes() throws Exception
    {
        assertEquals("Too many values for \"The question\". Given: 3. Allowed: 2.",
            this.validator.validate(
                ConstraintFixture.submission(this.context, Map.of(MAX_ANSWERS, 2L), "a", "b", "c"), REQUESTER));
    }

    // The pair's declared default is one value, and it must hold even where nothing was stored
    @Test
    void refusesASecondValueWhereNoMaximumWasStored() throws Exception
    {
        assertEquals("Too many values for \"The question\". Given: 2. Allowed: 1.",
            this.validator.validate(
                ConstraintFixture.submission(this.context, Map.of(), "a", "b"), REQUESTER));
    }

    // Clearing a field posts an empty value, and an emptied value must not count against the ceiling
    @Test
    void countsOnlyNonBlankValues() throws Exception
    {
        assertNull(this.validator.validate(
            ConstraintFixture.submission(this.context, Map.of(MAX_ANSWERS, 1L), "a", " "), REQUESTER));
    }

    // Zero or a negative maximum means the question takes any number of values
    @Test
    void ignoresQuestionsTakingAnyNumber() throws Exception
    {
        assertNull(this.validator.validate(
            ConstraintFixture.submission(this.context, Map.of(MAX_ANSWERS, 0L), "a", "b", "c", "d"), REQUESTER));
    }

    @Test
    void acceptsAnUnansweredQuestion() throws Exception
    {
        assertNull(this.validator.validate(
            ConstraintFixture.submission(this.context, Map.of(MAX_ANSWERS, 1L)), REQUESTER));
    }
}
