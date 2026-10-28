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
 * Unit tests for {@link PatternConstraint}: the whole value must match, the schema's own words are preferred, and a
 * broken pattern is loud rather than silently unenforced.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class PatternConstraintTest
{
    private static final String PATTERN = "pattern";

    private static final String DIGITS = "[0-9]+";

    private final SlingContext context = ConstraintFixture.newContext();

    private ConstraintFixture fixture;

    @BeforeEach
    void setUp()
    {
        this.fixture = ConstraintFixture.showingEverything(this.context);
    }

    @Test
    void acceptsAMatchingValue() throws Exception
    {
        assertNull(this.fixture.verdictOn(Map.of(PATTERN, DIGITS), "42"));
    }

    @Test
    void refusesWithTheQuestionsOwnMessage() throws Exception
    {
        assertEquals("Digits only.",
            this.fixture.verdictOn(Map.of(PATTERN, DIGITS, "patternMessage", "Digits only."), "abc"));
    }

    @Test
    void refusesQuotingThePatternWhenNoMessageIsConfigured() throws Exception
    {
        assertEquals("The answer to \"The question\" must match [0-9]+.",
            this.fixture.verdictOn(Map.of(PATTERN, DIGITS), "abc"));
    }

    // Matching a fragment is not matching: "42a" contains digits and is still not a number of digits
    @Test
    void requiresTheWholeValueToMatch() throws Exception
    {
        assertEquals("The answer to \"The question\" must match [0-9]+.",
            this.fixture.verdictOn(Map.of(PATTERN, DIGITS), "42a"));
    }

    @Test
    void ignoresUnpatternedQuestions() throws Exception
    {
        assertNull(this.fixture.verdictOn(Map.of(), "anything"));
    }

    @Test
    void refusesLoudlyOnABrokenPattern() throws Exception
    {
        assertEquals("The expected format for \"The question\" cannot be checked; this schema needs fixing.",
            this.fixture.verdictOn(Map.of(PATTERN, "[unclosed"), "x"));
    }

    @Test
    void refusesABrokenPatternEvenUnanswered() throws Exception
    {
        assertEquals("The expected format for \"The question\" cannot be checked; this schema needs fixing.",
            this.fixture.verdictOn(Map.of(PATTERN, "[unclosed")));
    }
}
