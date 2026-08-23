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
 * Unit tests for {@link PatternValidator}: the whole value must match, the schema's own words are preferred, and a
 * broken pattern is loud rather than silently unenforced.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class PatternValidatorTest
{
    private static final String REQUESTER = "someone";

    private static final String PATTERN = "pattern";

    private static final String DIGITS = "[0-9]+";

    // JCR-backed: a submission points at its schema version, and an answer at its question, with real REFERENCEs
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final PatternValidator validator = new PatternValidator();

    @Test
    void acceptsAMatchingValue() throws Exception
    {
        assertNull(this.validator.validate(
            ConstraintFixture.submission(this.context, Map.of(PATTERN, DIGITS), "42"), REQUESTER));
    }

    @Test
    void refusesWithTheQuestionsOwnMessage() throws Exception
    {
        assertEquals("Digits only.",
            this.validator.validate(
                ConstraintFixture.submission(this.context,
                    Map.of(PATTERN, DIGITS, "patternMessage", "Digits only."), "abc"),
                REQUESTER));
    }

    @Test
    void refusesQuotingThePatternWhenNoMessageIsConfigured() throws Exception
    {
        assertEquals("The answer to \"The question\" must match [0-9]+.",
            this.validator.validate(
                ConstraintFixture.submission(this.context, Map.of(PATTERN, DIGITS), "abc"), REQUESTER));
    }

    // Matching a fragment is not matching: "42a" contains digits and is still not a number of digits
    @Test
    void requiresTheWholeValueToMatch() throws Exception
    {
        assertEquals("The answer to \"The question\" must match [0-9]+.",
            this.validator.validate(
                ConstraintFixture.submission(this.context, Map.of(PATTERN, DIGITS), "42a"), REQUESTER));
    }

    // An emptied field stores a blank; whether something must be given at all is the answer-count pair's business
    @Test
    void passesOverBlankValues() throws Exception
    {
        assertNull(this.validator.validate(
            ConstraintFixture.submission(this.context, Map.of(PATTERN, DIGITS), " "), REQUESTER));
    }

    @Test
    void ignoresUnpatternedQuestions() throws Exception
    {
        assertNull(this.validator.validate(
            ConstraintFixture.submission(this.context, Map.of(), "anything"), REQUESTER));
    }

    // Accepting whatever comes while the schema is broken would silently run without the rule the schema states,
    // so a pattern that cannot be compiled refuses every save until the schema is fixed
    @Test
    void refusesLoudlyOnABrokenPattern() throws Exception
    {
        assertEquals("The expected format for \"The question\" cannot be checked; this schema needs fixing.",
            this.validator.validate(
                ConstraintFixture.submission(this.context, Map.of(PATTERN, "[unclosed"), "x"), REQUESTER));
    }

    @Test
    void refusesABrokenPatternEvenUnanswered() throws Exception
    {
        assertEquals("The expected format for \"The question\" cannot be checked; this schema needs fixing.",
            this.validator.validate(
                ConstraintFixture.submission(this.context, Map.of(PATTERN, "[unclosed")), REQUESTER));
    }
}
