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
 * Checks that a quote from the model is really in the document.
 *
 * <p>A quote that cannot be found is not evidence, however likely it sounds. This also stops a document that
 * tells the model what to say from creating its own evidence.
 *
 * <p>Both texts are lowercased, their whitespace collapsed, and the {@code <!-- page: N -->} markers removed,
 * since a marker can sit in the middle of a sentence.
 *
 * <p>A quote that is not there word for word is compared, by edit distance, to the part of the text closest to
 * it. Missing words cost as much as added ones, so a sentence joined from two places fails. Numbers and
 * negations must match exactly, because a change there changes the meaning.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class QuoteVerifier
{
    /** A quote shorter than this is too easy to match by chance, so it never counts as evidence. */
    private static final int MIN_QUOTE_LENGTH = 3;

    /** The lowest match score that counts as found. 0.85 allows a small slip, not a made-up sentence. */
    private static final double MATCH_FLOOR = 0.85;

    /** A quote longer than this must match exactly, because the close-match check is slow on long quotes. */
    private static final int MAX_FUZZY_QUOTE = 1000;

    /**
     * How many characters of the quote are searched for to find where it might start. A quote no longer than
     * this must match exactly.
     */
    private static final int ANCHOR_LENGTH = 24;

    /** The most places in the text the quote is compared against. */
    private static final int MAX_CANDIDATES = 4;

    /** The most places one anchor may add, so an opening that repeats in the text cannot take them all. */
    private static final int CANDIDATES_PER_ANCHOR = 2;

    /**
     * How far past a candidate start, in quote lengths, the compared text runs. It also starts half a quote
     * before, so it is about twice as long as the quote, leaving room for a few added words.
     */
    private static final double WINDOW_FACTOR = 1.5;

    /** Words that turn a statement into its opposite when added or removed. */
    private static final Set<String> NEGATION_WORDS =
        Set.of("not", "no", "never", "without", "none", "nor", "neither", "cannot");

    private static final Pattern MARKER_PATTERN =
        Pattern.compile("<!--\\s{0,10}page:\\s{0,10}\\d{1,9}\\s{0,10}-->");

    /** Any whitespace, including the no-break spaces converted documents are full of. */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern WORD_BREAK = Pattern.compile("[^\\p{L}\\p{N}]+");

    private QuoteVerifier()
    {
        // Utility
    }

    /**
     * Where a quote matched in the normalized text, and how well.
     *
     * @param score how well the quote matches there, 1 for an exact match
     * @param at where the match starts, -1 when nothing matched
     * @param end where the match ends, exclusive, -1 when nothing matched
     * @version $Id$
     * @since 0.1.0
     */
    private record Match(double score, int at, int end)
    {
        /** No match. */
        static Match createEmpty()
        {
            return new Match(0.0, -1, -1);
        }
    }

    /**
     * Finds where a quote is in a normalized text, which may be several documents joined together. A match that
     * crosses from one document into the next does not count, since those words were never next to each other.
     *
     * @param quote the quote as the model wrote it
     * @param haystack the normalized text to search, see {@link #normalize}
     * @param partStarts where each joined document starts in the text, empty for a single document
     * @return where the quote starts, or -1 when it is not found or is {@code null}
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
            return Match.createEmpty();
        }
        for (int exact = haystack.indexOf(wanted); exact >= 0; exact = haystack.indexOf(wanted, exact + 1)) {
            final int end = exact + wanted.length();
            if (!isAcrossParts(exact, end, partStarts)
                && (wanted.length() > ANCHOR_LENGTH || isOnWordEdges(haystack, exact, end))) {
                return new Match(1.0, exact, end);
            }
        }
        if (wanted.length() <= ANCHOR_LENGTH || wanted.length() > MAX_FUZZY_QUOTE) {
            return Match.createEmpty();
        }
        return findBestWindow(wanted, haystack, partStarts);
    }

    /**
     * Whether a match starts and ends where words do. A short quote is found inside longer words by chance, as
     * "yes" is in "eyes" and "100" in "1000", so it only counts as a whole word.
     */
    private static boolean isOnWordEdges(final String haystack, final int at, final int end)
    {
        return !isWordInside(haystack, at - 1, at) && !isWordInside(haystack, end - 1, end);
    }

    /**
     * Whether no word ends between {@code before} and {@code after}: both are part of a word, or a number goes on
     * past a separator, as in "2,100,000" or "11.5".
     */
    private static boolean isWordInside(final String text, final int before, final int after)
    {
        if (before < 0 || after >= text.length()) {
            return false;
        }
        final boolean isWordOnBothSides =
            Character.isLetterOrDigit(text.charAt(before)) && Character.isLetterOrDigit(text.charAt(after));
        return isWordOnBothSides || isSeparatorInNumber(text, after) || isSeparatorInNumber(text, before);
    }

    /** Whether the character at {@code at} is a comma or a point with a digit on both sides. */
    private static boolean isSeparatorInNumber(final String text, final int at)
    {
        final char character = text.charAt(at);
        return (character == ',' || character == '.') && isDigitAt(text, at - 1) && isDigitAt(text, at + 1);
    }

    private static boolean isDigitAt(final String text, final int at)
    {
        return at >= 0 && at < text.length() && Character.isDigit(text.charAt(at));
    }

    /** The best close match for the quote among the candidate places, or no match. */
    private static Match findBestWindow(final String wanted, final String haystack, final int[] partStarts)
    {
        final int window = (int) Math.min(Integer.MAX_VALUE, (long) (wanted.length() * WINDOW_FACTOR));
        Match best = Match.createEmpty();
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
     * The part of the text between {@code from} and {@code to} that needs the fewest edits to become the quote.
     * The whole quote is used, but the matched part may start and end anywhere in that range.
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

    /** The index of the lowest edit count, the first one on a tie. */
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

    /** Whether a match crosses the start of one of the joined documents. */
    private static boolean isAcrossParts(final int at, final int end, final int[] partStarts)
    {
        for (final int partStart : partStarts) {
            if (at < partStart && partStart < end) {
                return true;
            }
        }
        return false;
    }

    /** The numbers and negations in a match, widened to whole words at both ends. */
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
            if (NEGATION_WORDS.contains(word) || word.chars().anyMatch(Character::isDigit)) {
                keys.add(word);
            }
        }
        return keys;
    }

    /**
     * Places in the text where the quote might start.
     *
     * <p>They are found by searching for three pieces of the quote: its start, its middle and its end. A model
     * copying a passage rarely changes all three, and if it changed all three it did not copy it.</p>
     *
     * <p>Each piece may add at most two places, so the end piece only gets what the first two leave.</p>
     *
     * @param wanted the normalized quote
     * @param haystack the normalized text
     * @return up to {@link #MAX_CANDIDATES} positions in the text, empty when no piece is found
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
     * Adds the places where one piece of the quote says the quote might start, up to the limits.
     *
     * @param starts the places found so far; new ones are added to it
     * @param haystack the normalized text
     * @param anchor the piece of the quote to search for
     * @param anchorAt where that piece starts in the quote
     */
    private static void addCandidates(final List<Integer> starts, final String haystack, final String anchor,
        final int anchorAt)
    {
        int taken = 0;
        int found = haystack.indexOf(anchor);
        while (found >= 0 && taken < CANDIDATES_PER_ANCHOR && starts.size() < MAX_CANDIDATES) {
            // Where the whole quote would start, if this piece of it is the one found here
            final Integer start = Integer.valueOf(Math.max(0, found - anchorAt));
            if (!starts.contains(start)) {
                starts.add(start);
                taken++;
            }
            found = haystack.indexOf(anchor, found + 1);
        }
    }

    /**
     * Makes text ready to compare: page markers removed, whitespace collapsed, lowercased. Quotes and documents
     * both go through this, so they are always compared the same way.
     *
     * @param text the text to normalize
     * @return the normalized text
     */
    static String normalize(final String text)
    {
        final String noMarkers = MARKER_PATTERN.matcher(text).replaceAll(" ");
        return WHITESPACE_PATTERN.matcher(noMarkers).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }
}
