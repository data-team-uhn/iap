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
package io.uhndata.iap.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link CallBudget}: how much document text is left once the prompt and the answer have their
 * room in the model's context window.
 *
 * @version $Id$
 * @since 0.1.0
 */
class CallBudgetTest
{
    /** A window with no round numbers in it, so a wrong term in the sum cannot pass by luck. */
    private static final long WINDOW = 100_000L;

    private static LLMSettings settings(final long contextLimitTokens)
    {
        return new LLMSettings("prompter", new LLMSettings.ProviderSettings(null, null, 120, null), "model",
            new LLMSettings.ModelSettings(contextLimitTokens, 0.0, null, null));
    }

    @Test
    void leavesTheWindowLessTheMarginThePromptAndTheAnswer()
    {
        // 100000 - 15% - 2000 answer - 3000 prompt
        assertEquals(80_000, CallBudget.calculateDocumentTokenBudget(settings(WINDOW), 3000, 2000));
    }

    // A model that does not say what its window is is read as the smallest window any model has
    @Test
    void fallsBackToTheDefaultWindowWhenAModelSaysNothing()
    {
        assertEquals(CallBudget.calculateDocumentTokenBudget(CallBudget.DEFAULT_CONTEXT_LIMIT_TOKENS, 3000, 2000),
            CallBudget.calculateDocumentTokenBudget(settings(0), 3000, 2000));
    }

    @Test
    void readsANegativeWindowAsNoneAtAll()
    {
        assertEquals(CallBudget.calculateDocumentTokenBudget(CallBudget.DEFAULT_CONTEXT_LIMIT_TOKENS, 3000, 2000),
            CallBudget.calculateDocumentTokenBudget(settings(-1), 3000, 2000));
    }

    // A prompt that fills the window on its own leaves no room for the document, rather than a negative budget
    @Test
    void leavesNothingWhenThePromptFillsTheWindow()
    {
        assertEquals(0, CallBudget.calculateDocumentTokenBudget(settings(WINDOW), WINDOW, 2000));
    }

    @Test
    void countsANegativePromptOrAnswerAsNothing()
    {
        assertEquals(85_000, CallBudget.calculateDocumentTokenBudget(settings(WINDOW), -5, -5));
    }

    // The bigger the window, the more document fits: nothing else caps it
    @Test
    void givesABiggerWindowMoreRoom()
    {
        assertEquals(886_289, CallBudget.calculateDocumentTokenBudget(1_048_576, 3000, 2000));
    }

    @Test
    void estimatesTokensFromTheLengthOfTheText()
    {
        assertEquals(0, CallBudget.estimateTokens(null));
        assertEquals(0, CallBudget.estimateTokens(""));
        assertEquals(1, CallBudget.estimateTokens("x"));
        assertEquals(1, CallBudget.estimateTokens("xxxx"));
        assertEquals(2, CallBudget.estimateTokens("xxxxx"));
    }
}
