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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * {@code maxOutputTokens} comes out because a vLLM-style provider refuses a request outright when the prompt
 * plus the requested output would not fit; it does not stop generating early. The value to pass is the one the
 * call asks for through {@link LLMRequestOptions}, or 0 when it asks for none, since a provider left to pick
 * its own ceiling fits it into whatever the prompt leaves.
 * </p>
 *
 * <p>
 * The safety margin covers what this cannot count: {@link #CHARS_PER_TOKEN} is a rough estimate, and the
 * provider's chat template wraps every message in tokens of its own.
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
     * The window assumed when the active model does not say what its own is. The smallest window any model in the
     * catalog has, so a document that fits it fits whatever is actually serving the call.
     */
    public static final long DEFAULT_CONTEXT_LIMIT_TOKENS = 131072;

    /** The share of the window left free, so a rough token estimate cannot push a call past the real limit. */
    private static final double SAFETY_MARGIN_SHARE = 0.15;

    private CallBudget()
    {
        // Utility class, not meant to be instantiated
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
     * How many tokens of document text one call may carry, for a given context window.
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
}
