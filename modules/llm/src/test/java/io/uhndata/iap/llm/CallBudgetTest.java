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

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** What separates one paragraph from the next, which is where a cut is expected to land. */
    private static final String BREAK = "\n\n";

    /** Named so a test can say the opening survived a cut. */
    private static final String OPENING = "OPENING" + BREAK;

    /** Named so a test can say the end survived one. */
    private static final String ENDING = BREAK + "THE VERY END";

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

    @Test
    void handsBackTextThatAlreadyFits()
    {
        final String text = "x".repeat(40);
        final CallBudget.FittedText fitted = CallBudget.fitToTokens(text, 10);

        assertSame(text, fitted.text());
        assertFalse(fitted.wasCut());
        assertEquals(0, fitted.omittedCharacters());
    }

    @Test
    void handsBackNothingForNoTextAtAll()
    {
        assertEquals("", CallBudget.fitToTokens(null, 10).text());
        assertFalse(CallBudget.fitToTokens(null, 10).wasCut());
        assertEquals("", CallBudget.fitToTokens("", 10).text());
    }

    // The point of cutting in the middle: what is at the end of a protocol is what a reader would look up last,
    // and cutting at the end alone made exactly that unreadable
    @Test
    void keepsTheOpeningAndTheEndOfATextThatIsTooLong()
    {
        final String text = OPENING + "middle. ".repeat(500) + ENDING;
        final CallBudget.FittedText fitted = CallBudget.fitToTokens(text, 200);

        assertTrue(fitted.wasCut());
        assertTrue(fitted.text().startsWith("OPENING"));
        assertTrue(fitted.text().endsWith("THE VERY END"));
        assertTrue(fitted.text().contains(CallBudget.OMISSION_MARKER));
    }

    // Everything that was in the text is either sent or counted as left out; nothing goes missing quietly
    @Test
    void accountsForEveryCharacterItLeavesOut()
    {
        final String text = OPENING + "middle. ".repeat(500) + ENDING;
        final CallBudget.FittedText fitted = CallBudget.fitToTokens(text, 200);

        assertEquals(text.length(),
            fitted.text().length() - CallBudget.OMISSION_MARKER.length() + fitted.omittedCharacters());
    }

    @Test
    void movesBothCutsBackToAParagraphBreak()
    {
        final String text = "First paragraph." + BREAK + ("Filler paragraph." + BREAK).repeat(200)
            + "Last paragraph.";
        // Not split(), which reads its argument as a regular expression and the marker is full of brackets
        final String sent = CallBudget.fitToTokens(text, 200).text();
        final int gap = sent.indexOf(CallBudget.OMISSION_MARKER);

        assertTrue(gap > 0, "the two halves are joined by the marker");
        assertTrue(sent.substring(0, gap).endsWith("."), "the opening stops at the end of a paragraph");
        assertTrue(sent.substring(gap + CallBudget.OMISSION_MARKER.length()).startsWith("Filler paragraph."),
            "the end opens on a paragraph");
    }

    // Nothing better to cut on, so it is cut where the budget falls rather than refused
    @Test
    void cutsWhereTheBudgetFallsWhenThereAreNoParagraphs()
    {
        final CallBudget.FittedText fitted = CallBudget.fitToTokens("x".repeat(4000), 200);

        assertTrue(fitted.wasCut());
        assertTrue(fitted.text().length() <= 800);
        assertTrue(fitted.text().contains(CallBudget.OMISSION_MARKER));
    }

    @Test
    void sendsNothingWhenThereIsNoRoomEvenToSaySoAtAll()
    {
        final CallBudget.FittedText fitted = CallBudget.fitToTokens("x".repeat(400), 1);

        assertEquals("", fitted.text());
        assertTrue(fitted.wasCut());
        assertEquals(400, fitted.omittedCharacters());
    }

    @Test
    void sendsNothingForABudgetOfNothingOrLess()
    {
        assertEquals("", CallBudget.fitToTokens("x".repeat(400), 0).text());
        assertEquals("", CallBudget.fitToTokens("x".repeat(400), -10).text());
    }

    // A budget large enough to overflow an int has to leave the text alone, not throw
    @Test
    void handsBackTextForABudgetTooLargeToCount()
    {
        final String text = "x".repeat(40);

        assertSame(text, CallBudget.fitToTokens(text, Long.MAX_VALUE / 2).text());
    }

    @Test
    void readsTheBudgetFromTheActiveSettings() throws IOException
    {
        final LLMConfigurationService configuration = Mockito.mock(LLMConfigurationService.class);
        Mockito.when(configuration.getActiveSettings()).thenReturn(settings(WINDOW));

        assertEquals(WINDOW - 15_000 - 500 - 1000,
            CallBudget.calculateDocumentTokenBudget(configuration, 1000, 500, "gate"));
    }

    // Settings that cannot be read fall back to the smallest window any model has, rather than to no limit
    @Test
    void fallsBackToTheSmallestWindowWhenTheSettingsCannotBeRead() throws IOException
    {
        final LLMConfigurationService configuration = Mockito.mock(LLMConfigurationService.class);
        Mockito.when(configuration.getActiveSettings()).thenThrow(new IOException("no settings"));

        assertEquals(CallBudget.calculateDocumentTokenBudget(CallBudget.DEFAULT_CONTEXT_LIMIT_TOKENS, 1000, 500),
            CallBudget.calculateDocumentTokenBudget(configuration, 1000, 500, "gate"));
    }
}
