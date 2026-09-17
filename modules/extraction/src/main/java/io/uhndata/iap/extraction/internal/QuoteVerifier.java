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

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks that a quote is really in the text the model was shown.
 *
 * <p>The prompt asks for verbatim quotes. This is the code side of that rule. A quote we cannot find is
 * not evidence, however plausible it reads. That is what stops an instruction injected into a document
 * from inventing evidence.
 *
 * <p>Before comparing, both strings are lowercased, their whitespace is collapsed, and the
 * {@code <!-- page: N -->} and {@code [chunk:x]} markers are removed. The markers have to go because they
 * sit in the middle of sentences, so a quote spanning one would never match.
 *
 * <p>The score is how much of the quote appears in the text, in order, from 0 to 1. A quote that is there
 * word for word scores 1. Drop one word of ten and it scores about 0.9. Invent the sentence and it scores
 * near 0. {@link #MATCH_FLOOR} is where the line is drawn.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class QuoteVerifier
{
    /** Anything shorter than this is too easy to match by accident to count as evidence. */
    static final int MIN_QUOTE_LENGTH = 6;

    /** How much of a quote has to be there. 0.85 allows a slip or two, not a made-up sentence. */
    static final double MATCH_FLOOR = 0.85;

    /**
     * Past this length we only check whether the text contains the quote. The scan below is quadratic, so a
     * 10k quote against a 10k chunk would be 100M steps.
     */
    private static final int SCAN_LIMIT = 4000;

    private static final Pattern MARKERS = Pattern.compile(
        "<!--\\s{0,10}page:\\s{0,10}\\d{1,9}\\s{0,10}-->|\\[chunk:[^\\]]{1,200}\\]");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private QuoteVerifier()
    {
        // Utility
    }

    /**
     * How much of a quote appears in a text, from 0 to 1.
     *
     * @param quote what the model quoted
     * @param text the text it says the quote came from
     * @return the share of the quote that is there, 0 when there is nothing to compare
     */
    static double score(final String quote, final String text)
    {
        if (quote == null || text == null) {
            return 0.0;
        }
        final String wanted = normalize(quote);
        if (wanted.length() < MIN_QUOTE_LENGTH) {
            return 0.0;
        }
        final String haystack = normalize(text);
        if (haystack.contains(wanted)) {
            return 1.0;
        }
        if (wanted.length() > SCAN_LIMIT || haystack.length() > SCAN_LIMIT) {
            return 0.0;
        }
        return (double) longestCommonSubsequence(wanted, haystack) / wanted.length();
    }

    /**
     * Whether a quote appears in a text, allowing for the small slips a model makes when copying.
     *
     * @param quote what the model quoted
     * @param text the text it says the quote came from
     * @return {@code true} when enough of the quote is there
     */
    static boolean isVerbatim(final String quote, final String text)
    {
        return score(quote, text) >= MATCH_FLOOR;
    }

    /**
     * Longest common subsequence length. Only the previous row is ever read, so we keep two rows instead of
     * the whole table.
     */
    private static int longestCommonSubsequence(final String wanted, final String haystack)
    {
        int[] previous = new int[haystack.length() + 1];
        int[] current = new int[haystack.length() + 1];
        for (int i = 1; i <= wanted.length(); i++) {
            for (int j = 1; j <= haystack.length(); j++) {
                current[j] = wanted.charAt(i - 1) == haystack.charAt(j - 1)
                    ? previous[j - 1] + 1
                    : Math.max(previous[j], current[j - 1]);
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[haystack.length()];
    }

    /**
     * The shape both the check and the heading lookup compare in: markers gone, whitespace collapsed,
     * lowercased. Shared so the two cannot disagree about what counts as the same text.
     *
     * @param text what to normalize
     * @return the normalized form
     */
    static String normalizeForSearch(final String text)
    {
        return normalize(text);
    }

    private static String normalize(final String text)
    {
        final String noMarkers = MARKERS.matcher(text).replaceAll(" ");
        return WHITESPACE.matcher(noMarkers).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }
}
