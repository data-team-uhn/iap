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
 * Unit tests for {@link ValueRangeConstraint}: hard bounds on numbers, and nothing else's job taken on.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ValueRangeConstraintTest
{
    private static final String MIN_VALUE = "minValue";

    private static final String MAX_VALUE = "maxValue";

    private final SlingContext context = ConstraintFixture.newContext();

    private ConstraintFixture fixture;

    @BeforeEach
    void setUp()
    {
        this.fixture = ConstraintFixture.showingEverything(this.context);
    }

    @Test
    void acceptsAValueInsideTheBounds() throws Exception
    {
        assertNull(this.fixture.verdictOn(Map.of(MIN_VALUE, 1.0d, MAX_VALUE, 10.0d), "5"));
    }

    @Test
    void refusesBelowTheMinimum() throws Exception
    {
        assertEquals("The answer to \"The question\" must be at least 1.",
            this.fixture.verdictOn(Map.of(MIN_VALUE, 1.0d), "0.5"));
    }

    @Test
    void refusesAboveTheMaximum() throws Exception
    {
        assertEquals("The answer to \"The question\" must be at most 10.",
            this.fixture.verdictOn(Map.of(MAX_VALUE, 10.0d), "11"));
    }

    @Test
    void writesAFractionalBoundAsItIs() throws Exception
    {
        assertEquals("The answer to \"The question\" must be at least 0.5.",
            this.fixture.verdictOn(Map.of(MIN_VALUE, 0.5d), "0.25"));
    }

    @Test
    void writesALargeBoundWithoutAnExponent() throws Exception
    {
        assertEquals("The answer to \"The question\" must be at most 100000000000000000000.",
            this.fixture.verdictOn(Map.of(MAX_VALUE, 1e20d), "1e21"));
    }

    @Test
    void writesASmallBoundWithoutAnExponent() throws Exception
    {
        assertEquals("The answer to \"The question\" must be at least 0.0001.",
            this.fixture.verdictOn(Map.of(MIN_VALUE, 0.0001d), "0.00001"));
    }

    @Test
    void passesOverValuesThatAreNotNumbers() throws Exception
    {
        assertNull(this.fixture.verdictOn(Map.of(MIN_VALUE, 1.0d), "soon"));
    }

    @Test
    void ignoresUnboundedQuestions() throws Exception
    {
        assertNull(this.fixture.verdictOn(Map.of(), "99999"));
    }

    @Test
    void acceptsAnUnansweredQuestion() throws Exception
    {
        assertNull(this.fixture.verdictOn(Map.of(MIN_VALUE, 1.0d)));
    }
}
