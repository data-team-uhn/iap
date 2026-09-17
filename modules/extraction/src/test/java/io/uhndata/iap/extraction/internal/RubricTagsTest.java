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
package io.uhndata.iap.extraction.internal;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RubricTags}: the vocabulary both the gate and the intake read a model's tags through.
 *
 * @version $Id$
 * @since 0.1.0
 */
class RubricTagsTest
{
    @Test
    void holdsEveryRubricAndNothingElse()
    {
        assertEquals(17, RubricTags.ALL.size());
        assertEquals("B.1", RubricTags.ALL.get(0));
        assertEquals("B.17", RubricTags.ALL.get(16));
    }

    @Test
    void acceptsARubric()
    {
        for (final String tag : RubricTags.ALL) {
            assertTrue(RubricTags.isValid(tag), tag + " is a rubric");
        }
    }

    @Test
    void acceptsARubricTheModelPaddedWithSpace()
    {
        assertTrue(RubricTags.isValid("  B.3 "));
    }

    // The range ends at 17, and a number outside it reads as a rubric without being one
    @Test
    void refusesANumberPastTheEndOfTheVocabulary()
    {
        assertFalse(RubricTags.isValid("B.18"));
        assertFalse(RubricTags.isValid("B.0"));
        assertFalse(RubricTags.isValid("B.170"));
    }

    @Test
    void refusesSomethingThatIsNotATagAtAll()
    {
        assertFalse(RubricTags.isValid("Background"));
        assertFalse(RubricTags.isValid("b.3"));
        assertFalse(RubricTags.isValid(""));
        assertFalse(RubricTags.isValid(null));
    }

    @Test
    void keepsTheRubricsInTheOrderTheModelGaveThem()
    {
        assertEquals(List.of("B.5", "B.2"), RubricTags.filter(List.of("B.5", "B.2")));
    }

    @Test
    void dropsWhateverWasNotARubric()
    {
        assertEquals(List.of("B.4"), RubricTags.filter(List.of("Methods", "B.4", "B.99")));
    }

    // A chunk really can be two things at once, so nothing caps how many tags survive
    @Test
    void keepsAsManyTagsAsTheModelGave()
    {
        assertEquals(RubricTags.ALL, RubricTags.filter(RubricTags.ALL));
    }

    @Test
    void keepsEachTagOnce()
    {
        assertEquals(List.of("B.1", "B.2"), RubricTags.filter(List.of("B.1", "B.1", " B.1 ", "B.2")));
    }

    @Test
    void answersNothingForNothing()
    {
        assertEquals(List.of(), RubricTags.filter(List.of()));
        assertEquals(List.of(), RubricTags.filter(null));
        assertEquals(List.of(), RubricTags.filter(Arrays.asList((String) null)));
    }
}
