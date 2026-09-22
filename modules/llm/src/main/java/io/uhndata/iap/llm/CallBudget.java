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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How much document text one call may carry, worked out from the active model's context window.
 *
 * <p>
 * The window holds the whole call: the system prompt, the user message, and the answer the model is allowed to
 * generate. What is left for the document is {@code contextLimitTokens} less a safety margin, less
 * {@code maxOutputTokens}, less the tokens the prompt itself takes.
 * </p>
 *
 * <p>
 * A document too long for that is cut in the middle rather than at the end, so the call carries the document's
 * opening <em>and</em> its end. The end is where a protocol keeps its appendices, its site list and its signature
 * page, and cutting at the end alone made those unreadable for exactly the documents most likely to mention
 * them. The two halves are joined by {@link #OMISSION_MARKER}, so the model is told it is reading across a gap
 * rather than left to treat the join as one continuous text.
 * </p>
 *
 * <p>
 * {@code maxOutputTokens} comes out because a vLLM-style provider refuses a request outright when the prompt
 * plus the requested output would not fit; it does not stop generating early. The value to pass is the one the
 * call asks for through {@link LLMRequestOptions}, or 0 when it asks for none, since a provider left to pick
 * its own ceiling fits it into whatever the prompt leaves.
 * </p>
 *
 * <p>
 * The safety margin covers what this cannot count: {@link #CHARS_PER_TOKEN} is a rough estimate that
 * undercounts dense Markdown, and the provider's chat template wraps every message in tokens of its own.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class CallBudget
{
    /**
     * Roughly how many characters make a token. Only ever used to decide how much text will fit, so an estimate
     * this rough is enough, and it costs nothing next to tokenizing.
     */
    public static final int CHARS_PER_TOKEN = 4;

    /**
     * The window assumed when the active model does not say what its own is, or when the settings cannot be
     * read at all. The smallest window any model in the catalog has, so a document cut to it fits whatever is
     * actually serving the call.
     */
    public static final long DEFAULT_CONTEXT_LIMIT_TOKENS = 131072;

    /**
     * What goes between the two halves of a document that had to be cut. Said in the document's own voice because
     * that is where the model reads it: a join with nothing to mark it reads as one continuous text, and an answer
     * drawn across it would be drawn from two places at once.
     */
    public static final String OMISSION_MARKER =
        "\n\n[The middle of this document did not fit in one request and has been left out."
            + " What follows is the end of the document.]\n\n";

    /** The share of the window left free, so a rough token estimate cannot push a call past the real limit. */
    private static final double SAFETY_MARGIN_SHARE = 0.15;

    /**
     * The share of the room the opening gets when a document has to be cut. Three quarters, because that is where
     * the cover page, the synopsis and the objectives are, and most of what is asked is answered from them; the
     * remaining quarter is enough to reach the appendices and the signature page.
     */
    private static final double HEAD_SHARE = 0.75;

    /** What separates one paragraph from the next, which is where a cut is moved back to. */
    private static final String PARAGRAPH_BREAK = "\n\n";

    private static final Logger LOGGER = LoggerFactory.getLogger(CallBudget.class);

    private CallBudget()
    {
        // Utility class, not meant to be instantiated
    }

    /**
     * How many tokens of document text one call may carry, for whatever model is active right now.
     *
     * <p>Every stage that sends a document asks this same question and answers a settings failure the same way,
     * so it is asked here once rather than three times over. Settings that cannot be read at all fall back to
     * the smallest window any model in the catalog has, which fits whatever is actually serving the call.</p>
     *
     * @param configuration where the active model's settings are read from
     * @param promptTokens the tokens everything other than the document takes
     * @param maxOutputTokens the maximum output tokens this call asks for
     * @param stage what is asking, for the warning when the settings cannot be read
     * @return the token budget for the document
     */
    public static long calculateDocumentTokenBudget(@NotNull final LLMConfigurationService configuration,
        final long promptTokens, final long maxOutputTokens, @NotNull final String stage)
    {
        try {
            return calculateDocumentTokenBudget(configuration.getActiveSettings(), promptTokens, maxOutputTokens);
        } catch (final IOException e) {
            LOGGER.warn("Could not read the active LLM settings for the {} budget: {}", stage, e.getMessage());
            return calculateDocumentTokenBudget(DEFAULT_CONTEXT_LIMIT_TOKENS, promptTokens, maxOutputTokens);
        }
    }

    /**
     * How many tokens of document text one call may carry, for the active model.
     *
     * @param settings the active model's settings
     * @param promptTokens the tokens everything other than the document takes: the system prompt and the fixed
     *            part of the user message
     * @param maxOutputTokens the maximum output tokens this call asks for
     * @return the token budget for the document, never negative
     */
    public static long calculateDocumentTokenBudget(@NotNull final LLMSettings settings, final long promptTokens,
        final long maxOutputTokens)
    {
        return calculateDocumentTokenBudget(settings.getContextLimitTokens(), promptTokens, maxOutputTokens);
    }

    /**
     * How many tokens of document text one call may carry, for a given context window. A caller that could not
     * read the settings at all passes {@link #DEFAULT_CONTEXT_LIMIT_TOKENS}.
     *
     * @param contextLimitTokens the model's context window, or 0 when it does not say
     * @param promptTokens the tokens everything other than the document takes: the system prompt and the fixed
     *            part of the user message
     * @param maxOutputTokens the maximum output tokens this call asks for
     * @return the token budget for the document, never negative
     */
    public static long calculateDocumentTokenBudget(final long contextLimitTokens, final long promptTokens,
        final long maxOutputTokens)
    {
        final long window = contextLimitTokens > 0 ? contextLimitTokens : DEFAULT_CONTEXT_LIMIT_TOKENS;
        final long margin = (long) Math.ceil(window * SAFETY_MARGIN_SHARE);
        return Math.max(0, window - margin - Math.max(0, maxOutputTokens) - Math.max(0, promptTokens));
    }

    /**
     * Roughly how many tokens a piece of text takes, rounded up.
     *
     * @param text the text, may be {@code null}
     * @return the estimated tokens, 0 for no text
     */
    public static long estimateTokens(@Nullable final String text)
    {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return ((long) text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /**
     * What one call carries of a text, and what it had to leave out.
     *
     * @param text the text to send, empty when not even the opening fits
     * @param wasCut whether anything had to be left out
     * @param omittedCharacters how many characters were left out, 0 when none were
     * @version $Id$
     * @since 0.1.0
     */
    public record FittedText(@NotNull String text, boolean wasCut, long omittedCharacters)
    {
        /** The whole of a text that fitted as it was. */
        private static FittedText whole(final String text)
        {
            return new FittedText(text, false, 0);
        }
    }

    /**
     * The text as one call can carry it: whole when it fits, otherwise its opening and its end with the middle
     * left out.
     *
     * <p>Both cuts are moved back to a paragraph break, so neither half starts or stops mid-sentence. A text with
     * no paragraph breaks at all is cut where the budget falls, since there is nothing better to cut on.</p>
     *
     * @param text the text, may be {@code null}
     * @param tokenBudget how many tokens of it may be sent
     * @return what to send and what was left out, never {@code null}
     */
    @NotNull
    public static FittedText fitToTokens(@Nullable final String text, final long tokenBudget)
    {
        if (text == null || text.isEmpty()) {
            return FittedText.whole("");
        }
        final int limit = charactersFor(tokenBudget);
        if (text.length() <= limit) {
            return FittedText.whole(text);
        }
        if (limit <= OMISSION_MARKER.length()) {
            // Not even room to say that something was left out, so there is nothing worth sending
            return new FittedText("", true, text.length());
        }
        final int room = limit - OMISSION_MARKER.length();
        final int headEnd = endOfHead(text, (int) (room * HEAD_SHARE));
        // Whatever moving the head back to a paragraph break gave up goes to the end, rather than being lost
        final int tailStart = startOfTail(text, text.length() - (room - headEnd));
        return new FittedText(text.substring(0, headEnd) + OMISSION_MARKER + text.substring(tailStart), true,
            (long) tailStart - headEnd);
    }

    /**
     * How many characters a token budget is worth, without overflowing on a budget larger than any text.
     *
     * @param tokenBudget how many tokens may be sent
     * @return the character limit, never negative
     */
    private static int charactersFor(final long tokenBudget)
    {
        final long budget = Math.max(0, tokenBudget);
        if (budget >= Integer.MAX_VALUE / CHARS_PER_TOKEN) {
            // More room than any String can be long, so multiplying it out would only overflow
            return Integer.MAX_VALUE;
        }
        return (int) budget * CHARS_PER_TOKEN;
    }

    /**
     * Where the opening stops: the last paragraph break at or before the room it has.
     *
     * @param text the whole text
     * @param at the furthest the opening may reach
     * @return where to stop, {@code at} when there is no paragraph break to stop at
     */
    private static int endOfHead(final String text, final int at)
    {
        final int paragraph = text.lastIndexOf(PARAGRAPH_BREAK, at);
        return paragraph <= 0 ? at : paragraph;
    }

    /**
     * Where the end starts: the first paragraph break at or after the room it has, so it opens on a paragraph
     * rather than halfway through one.
     *
     * @param text the whole text
     * @param at the earliest the end may start
     * @return where to start, {@code at} when there is no paragraph break to start at
     */
    private static int startOfTail(final String text, final int at)
    {
        final int paragraph = text.indexOf(PARAGRAPH_BREAK, at);
        return paragraph < 0 ? at : paragraph + PARAGRAPH_BREAK.length();
    }
}
