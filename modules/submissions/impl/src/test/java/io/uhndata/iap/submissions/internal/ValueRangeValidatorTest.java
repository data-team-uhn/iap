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
 * Unit tests for {@link ValueRangeValidator}: hard bounds on numbers, and nothing else's job taken on.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ValueRangeValidatorTest
{
    private static final String REQUESTER = "someone";

    private static final String MIN_VALUE = "minValue";

    private static final String MAX_VALUE = "maxValue";

    // JCR-backed: a submission points at its schema version, and an answer at its question, with real REFERENCEs
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final ValueRangeValidator validator = new ValueRangeValidator();

    @Test
    void acceptsAValueInsideTheBounds() throws Exception
    {
        assertNull(this.validator.validate(
            ConstraintFixture.submission(this.context, Map.of(MIN_VALUE, 1.0d, MAX_VALUE, 10.0d), "5"),
            REQUESTER));
    }

    @Test
    void refusesBelowTheMinimum() throws Exception
    {
        assertEquals("The answer to \"The question\" must be at least 1.",
            this.validator.validate(
                ConstraintFixture.submission(this.context, Map.of(MIN_VALUE, 1.0d), "0.5"), REQUESTER));
    }

    @Test
    void refusesAboveTheMaximum() throws Exception
    {
        assertEquals("The answer to \"The question\" must be at most 10.",
            this.validator.validate(
                ConstraintFixture.submission(this.context, Map.of(MAX_VALUE, 10.0d), "11"), REQUESTER));
    }

    // A bound is written the way its author wrote it: 0.5 stays 0.5, 1 does not become 1.0
    @Test
    void writesAFractionalBoundAsItIs() throws Exception
    {
        assertEquals("The answer to \"The question\" must be at least 0.5.",
            this.validator.validate(
                ConstraintFixture.submission(this.context, Map.of(MIN_VALUE, 0.5d), "0.25"), REQUESTER));
    }

    // Whether an answer fits its declared data type is a different question from whether its number is in range
    @Test
    void passesOverValuesThatAreNotNumbers() throws Exception
    {
        assertNull(this.validator.validate(
            ConstraintFixture.submission(this.context, Map.of(MIN_VALUE, 1.0d), "soon"), REQUESTER));
    }

    // An emptied field stores a blank, and a blank is no number to judge
    @Test
    void passesOverBlankValues() throws Exception
    {
        assertNull(this.validator.validate(
            ConstraintFixture.submission(this.context, Map.of(MIN_VALUE, 1.0d), " "), REQUESTER));
    }

    @Test
    void ignoresUnboundedQuestions() throws Exception
    {
        assertNull(this.validator.validate(
            ConstraintFixture.submission(this.context, Map.of(), "99999"), REQUESTER));
    }

    @Test
    void acceptsAnUnansweredQuestion() throws Exception
    {
        assertNull(this.validator.validate(
            ConstraintFixture.submission(this.context, Map.of(MIN_VALUE, 1.0d)), REQUESTER));
    }
}
