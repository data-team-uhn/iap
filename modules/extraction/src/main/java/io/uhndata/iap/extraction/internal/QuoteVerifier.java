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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks that a quote is really in the text the model was shown.
 *
 * <p>The prompt asks for verbatim quotes. This is the code side of that rule. A quote we cannot find is
 * not evidence, however plausible it reads. That is what stops an instruction injected into a document
 * from inventing evidence.
 *
 * <p>Both strings are lowercased, their whitespace collapsed, and the {@code <!-- page: N -->} markers removed,
 * since a marker can sit in the middle of a sentence.
 *
 * <p>A quote that is not there word for word is scored against the stretch of text that matches it best, by
 * edit distance, so text the model left out costs as much as text it added. A sentence stitched from two places
 * fails. Numbers and negations have to agree exactly: a slip there changes the meaning, not the wording.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class QuoteVerifier
{
    /** Anything shorter than this is too easy to match by accident to count as evidence. */
    private static final int MIN_QUOTE_LENGTH = 6;

    /** How much of a quote has to be right. 0.85 allows a slip or two, not a made-up sentence. */
    private static final double MATCH_FLOOR = 0.85;

    /** Past this length a quote is only checked for exact containment, since the comparison is quadratic. */
    private static final int MAX_FUZZY_QUOTE = 1000;

    /**
     * How many characters of the quote are used to find where it might start. A quote no longer than this has
     * to be there exactly.
     */
    private static final int ANCHOR_LENGTH = 24;

    /** How many candidate places are compared. The quote is in one of them or in none of them. */
    private static final int MAX_CANDIDATES = 4;

    /** How many of those one anchor may claim, so a repeating opening cannot use up the lot. */
    private static final int CANDIDATES_PER_ANCHOR = 2;

    /** How much longer than the quote the compared stretch is, for a model that added a few words. */
    private static final double WINDOW_FACTOR = 1.5;

    /** Words whose loss or addition turns a statement around. */
    private static final Set<String> NEGATIONS =
        Set.of("not", "no", "never", "without", "none", "nor", "neither", "cannot");

    private static final Pattern MARKERS =
        Pattern.compile("<!--\\s{0,10}page:\\s{0,10}\\d{1,9}\\s{0,10}-->");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern WORD_BREAK = Pattern.compile("[^\\p{L}\\p{N}]+");

    private QuoteVerifier()
    {
        // Utility
    }

    /**
     * Where a quote matched: the stretch of the normalized text it lines up with, and how well.
     *
     * @param score how right the quote is there, from 0 to 1
     * @param at where the stretch starts, -1 when nothing matched
     * @param end where the stretch ends, exclusive
     * @version $Id$
     * @since 0.1.0
     */
    private record Match(double score, int at, int end)
    {
        /** Nothing found. */
        static Match none()
        {
            return new Match(0.0, -1, -1);
        }
    }

    /**
     * Where a quote sits in a normalized text, which may be several documents joined into one. A match running
     * across the start of a document is not one: those words were never next to each other.
     *
     * @param quote what the model quoted, in its raw form
     * @param haystack the normalized text to look in, see {@link #normalize}
     * @param partStarts where each joined document starts in it, empty for a single one
     * @return where the quote starts, or -1 when enough of it is not there
     */
    static int locateIn(final String quote, final String haystack, final int[] partStarts)
    {
        if (quote == null || haystack == null) {
            return -1;
        }
        final Match found = match(normalize(quote), haystack, partStarts);
        return found.score() >= MATCH_FLOOR ? found.at() : -1;
    }

    private static Match match(final String wanted, final String haystack, final int[] partStarts)
    {
        if (wanted.length() < MIN_QUOTE_LENGTH) {
            return Match.none();
        }
        for (int exact = haystack.indexOf(wanted); exact >= 0; exact = haystack.indexOf(wanted, exact + 1)) {
            if (!isAcrossParts(exact, exact + wanted.length(), partStarts)) {
                return new Match(1.0, exact, exact + wanted.length());
            }
        }
        if (wanted.length() <= ANCHOR_LENGTH || wanted.length() > MAX_FUZZY_QUOTE) {
            return Match.none();
        }
        return findBestWindow(wanted, haystack, partStarts);
    }

    /** The best stretch any plausible starting place gives this quote. */
    private static Match findBestWindow(final String wanted, final String haystack, final int[] partStarts)
    {
        final int window = (int) Math.min(Integer.MAX_VALUE, (long) (wanted.length() * WINDOW_FACTOR));
        Match best = Match.none();
        for (final int start : findCandidateStarts(wanted, haystack)) {
            final int from = Math.max(0, start - wanted.length() / 2);
            final int to = Math.min(haystack.length(), from + window + wanted.length() / 2);
            final Match aligned = align(wanted, haystack, from, to);
            if (aligned.score() > best.score() && !isAcrossParts(aligned.at(), aligned.end(), partStarts)
                && hasSameKeyWords(wanted, getWords(haystack, aligned.at(), aligned.end()))) {
                best = aligned;
            }
        }
        return best;
    }

    /**
     * The stretch of the text between {@code from} and {@code to} with the fewest edits to the quote. The quote
     * is used whole; the stretch may start and end anywhere.
     */
    private static Match align(final String wanted, final String haystack, final int from, final int to)
    {
        final int length = to - from;
        int[] previous = new int[length + 1];
        int[] current = new int[length + 1];
        int[] previousStart = new int[length + 1];
        int[] currentStart = new int[length + 1];
        for (int j = 0; j <= length; j++) {
            previousStart[j] = j;
        }
        for (int i = 1; i <= wanted.length(); i++) {
            current[0] = i;
            currentStart[0] = 0;
            for (int j = 1; j <= length; j++) {
                final int same = previous[j - 1] + (wanted.charAt(i - 1) == haystack.charAt(from + j - 1) ? 0 : 1);
                final int skipQuote = previous[j] + 1;
                final int skipText = current[j - 1] + 1;
                if (same <= skipQuote && same <= skipText) {
                    current[j] = same;
                    currentStart[j] = previousStart[j - 1];
                } else if (skipQuote <= skipText) {
                    current[j] = skipQuote;
                    currentStart[j] = previousStart[j];
                } else {
                    current[j] = skipText;
                    currentStart[j] = currentStart[j - 1];
                }
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
            final int[] swapStart = previousStart;
            previousStart = currentStart;
            currentStart = swapStart;
        }
        final int end = findLowest(previous);
        final double score = 1.0 - (double) previous[end] / wanted.length();
        return new Match(score, from + previousStart[end], from + end);
    }

    /** Where the lowest edit count is, the first of them on a tie. */
    private static int findLowest(final int[] costs)
    {
        int lowest = 0;
        for (int j = 1; j < costs.length; j++) {
            if (costs[j] < costs[lowest]) {
                lowest = j;
            }
        }
        return lowest;
    }

    /** Whether a stretch runs over the start of one of the joined documents. */
    private static boolean isAcrossParts(final int at, final int end, final int[] partStarts)
    {
        for (final int partStart : partStarts) {
            if (at < partStart && partStart < end) {
                return true;
            }
        }
        return false;
    }

    /** The key words of a stretch, widened to whole words at both ends. */
    private static Set<String> getWords(final String haystack, final int at, final int end)
    {
        int from = at;
        while (from > 0 && Character.isLetterOrDigit(haystack.charAt(from - 1))) {
            from--;
        }
        int to = end;
        while (to < haystack.length() && Character.isLetterOrDigit(haystack.charAt(to))) {
            to++;
        }
        return getKeyWords(haystack.substring(from, to));
    }

    /** Whether the quote and the text it matched agree on every number and negation. */
    private static boolean hasSameKeyWords(final String wanted, final Set<String> matched)
    {
        return getKeyWords(wanted).equals(matched);
    }

    /** The numbers and negations in a text. */
    private static Set<String> getKeyWords(final String text)
    {
        final Set<String> keys = new HashSet<>();
        for (final String word : WORD_BREAK.split(text)) {
            if (NEGATIONS.contains(word) || word.chars().anyMatch(Character::isDigit)) {
                keys.add(word);
            }
        }
        return keys;
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
    private static List<Integer> findCandidateStarts(final String wanted, final String haystack)
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
