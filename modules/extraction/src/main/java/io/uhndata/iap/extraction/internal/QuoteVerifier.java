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

import java.util.ArrayList;
import java.util.List;
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
 * {@code <!-- page: N -->} markers are removed. The markers have to go because they sit in the middle of
 * sentences, so a quote spanning one would never match.
 *
 * <p>The score is how much of the quote appears in the text, in order, from 0 to 1. A quote that is there
 * word for word scores 1. Drop one word of ten and it scores about 0.9. {@link #MATCH_FLOOR} is where the line
 * is drawn.
 *
 * <p>What a made-up sentence fails is the anchoring below, not the score. The score is a character-level common
 * subsequence, and two pieces of prose on the same subject share a fair amount of one, so on its own it would
 * not tell them apart. A sentence the model built rather than copied matches none of the three anchors, so no
 * window is ever compared and nothing scores at all.
 *
 * <p><strong>The fuzzy comparison runs in a window, not over the whole document.</strong> Comparing a quote
 * against a whole protocol is quadratic, so it used to be given up on for any text past a few thousand
 * characters — which is every real document, leaving nothing but exact containment and quietly halving the
 * confidence of every answer whose quote was a word out. Instead, the few places the quote could plausibly
 * start are found first, cheaply, and only a stretch of text around each of them is compared.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class QuoteVerifier
{
    /** Anything shorter than this is too easy to match by accident to count as evidence. */
    private static final int MIN_QUOTE_LENGTH = 6;

    /** How much of a quote has to be there. 0.85 allows a slip or two, not a made-up sentence. */
    private static final double MATCH_FLOOR = 0.85;

    /**
     * Past this length a quote is only ever checked for exact containment. The comparison below is quadratic
     * in the quote's length, and a passage this long that is not there verbatim is a passage the model built
     * rather than copied.
     */
    private static final int MAX_FUZZY_QUOTE = 1000;

    /**
     * How many characters of the quote are used to find where it might start. Long enough not to match every
     * other sentence, short enough to survive the model changing a word near the beginning.
     */
    private static final int ANCHOR_LENGTH = 24;

    /** How many candidate places are compared. The quote is in one of them or in none of them. */
    private static final int MAX_CANDIDATES = 4;

    /** How many of those one anchor may claim, so a repeating opening cannot use up the lot. */
    private static final int CANDIDATES_PER_ANCHOR = 2;

    /**
     * How much longer than the quote the compared stretch is. A model that drops words needs no more room than
     * the quote itself; one that adds a few needs a little.
     */
    private static final double WINDOW_FACTOR = 1.5;

    private static final Pattern MARKERS =
        Pattern.compile("<!--\\s{0,10}page:\\s{0,10}\\d{1,9}\\s{0,10}-->");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private QuoteVerifier()
    {
        // Utility
    }

    /**
     * How well a quote did somewhere in the text, and where that was.
     *
     * @param score the share of the quote found there, from 0 to 1
     * @param at where it starts in the normalized text, -1 when it was not found at all
     * @version $Id$
     * @since 0.1.0
     */
    private record Match(double score, int at)
    {
        /** Nothing found. */
        static Match none()
        {
            return new Match(0.0, -1);
        }
    }

    /**
     * Where a quote sits in a text that has already been normalized.
     *
     * <p>One search, not two. The caller wants both whether the quote is there and where it is, and looking for
     * it once answers both. Asking again afterwards would only find the quotes that are there word for word,
     * which leaves the ones this fuzzy match exists for with nowhere to point.</p>
     *
     * @param quote what the model quoted, in its raw form
     * @param haystack the normalized text to look in, see {@link #normalize}
     * @return where the quote starts, or -1 when enough of it is not there
     */
    static int locateIn(final String quote, final String haystack)
    {
        if (quote == null || haystack == null) {
            return -1;
        }
        final Match found = match(normalize(quote), haystack);
        return found.score() >= MATCH_FLOOR ? found.at() : -1;
    }

    /**
     * The best any plausible position in the text does with this quote.
     *
     * @param wanted the normalized quote
     * @param haystack the normalized text
     * @return the best match, scoring 0 when there is nothing to compare
     */
    private static Match match(final String wanted, final String haystack)
    {
        if (wanted.length() < MIN_QUOTE_LENGTH) {
            return Match.none();
        }
        final int exact = haystack.indexOf(wanted);
        if (exact >= 0) {
            return new Match(1.0, exact);
        }
        if (wanted.length() > MAX_FUZZY_QUOTE) {
            return Match.none();
        }
        return bestWindowScore(wanted, haystack);
    }

    /**
     * The best score any plausible position in the text gives this quote.
     *
     * @param wanted the normalized quote
     * @param haystack the normalized text
     * @return the best match, scoring 0 when there is no candidate at all
     */
    private static Match bestWindowScore(final String wanted, final String haystack)
    {
        final int window = (int) Math.min(Integer.MAX_VALUE, (long) (wanted.length() * WINDOW_FACTOR));
        Match best = Match.none();
        for (final int start : candidateStarts(wanted, haystack)) {
            final int from = Math.max(0, start - wanted.length() / 2);
            final int to = Math.min(haystack.length(), from + window + wanted.length() / 2);
            final int common = longestCommonSubsequence(wanted, haystack.substring(from, to));
            final double score = (double) common / wanted.length();
            if (score > best.score()) {
                // Where the anchor says the quote begins, not where the compared stretch begins: the stretch
                // reaches back before the quote, and a heading looked up from there could be the one above
                // the section the quote is really in
                best = new Match(score, start);
            }
            if (best.score() >= 1.0) {
                break;
            }
        }
        return best;
    }

    /**
     * Where in the text the quote might start.
     *
     * <p>Anchored on stretches of the quote itself: its opening, its middle and its end. A model copying a
     * passage rarely changes all three, and one that has changed all three did not copy it.</p>
     *
     * <p>Each anchor gets its own share of the candidates rather than first come, first served. An opening that
     * repeats through a protocol - a numbered heading, a recurring phrase - would otherwise use the whole
     * budget on its own occurrences, and the place the middle anchor would have found is never compared.</p>
     *
     * @param wanted the normalized quote
     * @param haystack the normalized text
     * @return up to {@link #MAX_CANDIDATES} offsets into the text, in no particular order
     */
    private static List<Integer> candidateStarts(final String wanted, final String haystack)
    {
        final List<Integer> starts = new ArrayList<>(MAX_CANDIDATES);
        final int anchorLength = Math.min(ANCHOR_LENGTH, wanted.length());
        final int[] anchorsAt = { 0, (wanted.length() - anchorLength) / 2, wanted.length() - anchorLength };
        for (final int anchorAt : anchorsAt) {
            addCandidates(starts, haystack, wanted.substring(anchorAt, anchorAt + anchorLength), anchorAt);
        }
        return starts;
    }

    /**
     * Where one anchor says the quote might start, for as many places as this anchor is allowed.
     *
     * @param starts the candidates so far, added to
     * @param haystack the normalized text
     * @param anchor the stretch of the quote to look for
     * @param anchorAt where that stretch sits in the quote
     */
    private static void addCandidates(final List<Integer> starts, final String haystack, final String anchor,
        final int anchorAt)
    {
        int taken = 0;
        int found = haystack.indexOf(anchor);
        while (found >= 0 && taken < CANDIDATES_PER_ANCHOR && starts.size() < MAX_CANDIDATES) {
            // Where the quote would start if this anchor is the part of it that matched
            final Integer start = Integer.valueOf(Math.max(0, found - anchorAt));
            if (!starts.contains(start)) {
                starts.add(start);
                taken++;
            }
            found = haystack.indexOf(anchor, found + 1);
        }
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
    static String normalize(final String text)
    {
        final String noMarkers = MARKERS.matcher(text).replaceAll(" ");
        return WHITESPACE.matcher(noMarkers).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }
}
