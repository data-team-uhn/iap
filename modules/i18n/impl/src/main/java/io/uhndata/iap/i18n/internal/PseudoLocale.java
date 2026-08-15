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
package io.uhndata.iap.i18n.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import com.ibm.icu.text.MessagePattern;

/**
 * Turns a message into a pseudo-translation of itself.
 *
 * <p>Two of them, because interfaces break at both extremes: German runs about a third longer than English
 * and Chinese about half as long, and a layout that survives one can fail the other. {@link Style#ACCENTED}
 * is the long one, and is bracketed so that a string cut off at either end or glued to a neighbour is
 * obvious; {@link Style#SHORTENED} is the short one, and is deliberately left unmarked, since brackets would
 * add back the width it exists to remove.</p>
 *
 * <p>The short one also renders right-to-left, which makes it a simulation of a real family of languages
 * rather than two unrelated checks: Hebrew and Arabic are both more compact than English and both read the
 * other way, so a layout meeting them meets them together.</p>
 *
 * <p>These are generated from the source language, never written by hand. A hand-written pseudo-locale is
 * partial, so a missing key falls back to plain English and reads exactly like a string that was never
 * translatable in the first place — which defeats the entire purpose. Generated, every key is present, the
 * fallback never fires, and any plain English left on screen is provably a string that never went through a
 * catalog.</p>
 *
 * <p><strong>It finds hardcoded strings, not missing translations.</strong> Those are different faults and
 * the key check is what catches the other one.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class PseudoLocale
{
    /** The region asking for the long, accented pseudo-locale, following Android and Chrome. */
    private static final String ACCENTED_REGION = "XA";

    /** The region asking for the short one. */
    private static final String SHORTENED_REGION = "XB";

    /**
     * How to disfigure a message.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public enum Style
    {
        /** Accented and padded, bracketed at both ends: the "everything is longer" case. */
        ACCENTED,
        /** Devowelled and mirrored, unmarked: the "compact and right-to-left" case. */
        SHORTENED
    }

    /** Latin letters and the lookalikes they map to, in the same order. */
    private static final String PLAIN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final String ACCENTED = "áƀçðéƒĝĥíĵķĺɱñóþqŕšţúṽŵẋýžÁƁÇÐÉƑĜĤÍĴĶĹṀÑÓÞQŔŠŢÚṼŴẊÝŽ";

    /** How much longer the accented form runs, which is roughly what a German translation costs. */
    private static final double EXPANSION = 1.4;

    /**
     * The letters the shortened form drops.
     *
     * <p>Dropping vowels rather than cutting the text short, which is what this did first and what made it
     * unusable. Shipped text is written in Markdown, so cutting at a measured fraction of the length lands
     * mid-token as often as not: {@code **faster**} came out as {@code **faster*}, and one unbalanced
     * delimiter changes how everything after it is parsed. Removing letters cannot do that -- it never
     * touches a character that is not a letter, so every delimiter, apostrophe and brace survives exactly
     * where it was, and the result is still readable enough to recognise on screen.</p>
     */
    private static final String VOWELS = "aeiouAEIOU";

    /**
     * Renders what follows right-to-left whatever direction its characters naturally have, until popped.
     *
     * <p>An override rather than a mark or an isolate: only this makes Latin letters lay out the other way,
     * which is the point. A mark ({@code U+200F}) is merely an invisible strong character and an isolate
     * ({@code U+2067}) contains direction without overriding it, so English inside either still reads
     * left-to-right — and the mirroring being tested for would never appear.</p>
     *
     * <p>Wrapping rather than reversing the letters, which is the other way to fake this. The letters
     * keep their order, so a screen reader still reads the words as words, they can still be copied, and a
     * test still finds them by what survives of what they say — only the rendering turns around. Reversal
     * would corrupt all three to achieve the same picture.</p>
     */
    private static final String RIGHT_TO_LEFT_OVERRIDE = "\u202e";

    /** Ends the override. Unbalanced, it would turn the rest of the page around with it. */
    private static final String POP_DIRECTION = "\u202c";

    private PseudoLocale()
    {
        // Utility class
    }

    /**
     * Which pseudo-locale, if any, a language names.
     *
     * <p>XA and XB are user-assigned region codes, so they can never collide with a real locale — the same
     * convention Android and Chrome use for the same purpose.</p>
     *
     * @param locale the language being asked for
     * @return how to disfigure the messages, or {@code null} for an ordinary language
     */
    public static Style styleOf(final Locale locale)
    {
        if (locale == null || !Locale.ENGLISH.getLanguage().equals(locale.getLanguage())) {
            return null;
        }
        if (ACCENTED_REGION.equals(locale.getCountry())) {
            return Style.ACCENTED;
        }
        if (SHORTENED_REGION.equals(locale.getCountry())) {
            return Style.SHORTENED;
        }
        return null;
    }

    /**
     * Rewrites the readable text of a message, leaving everything the formatter reads untouched.
     *
     * @param pattern an ICU MessageFormat pattern
     * @param style which pseudo-locale to produce
     * @return the pattern with its literal text disfigured
     */
    public static String transform(final String pattern, final Style style)
    {
        final MessagePattern parsed;
        try {
            parsed = new MessagePattern(pattern);
        } catch (final IllegalArgumentException e) {
            // Not every string reaching here is an ICU pattern. Configured content is ordinary prose, and a
            // stray brace in it is a typo at worst — certainly not a reason to fail the page. Treated as
            // readable text throughout, which is exactly what it is.
            return wrap(disfigure(pattern, style), style, pattern.length());
        }
        final StringBuilder result = new StringBuilder(pattern.length() * 2);
        int readableLength = 0;
        int cursor = 0;
        // Whether the text we are walking over is something a reader sees, rather than something the
        // formatter reads. Tracked positionally, and that is the whole trick: the type keyword of a complex
        // argument -- `plural`, `select` -- is carried on the ARG_START part and has no part of its own, so
        // masking parts by type does not protect it and produces `plúrál`, which no longer parses. What is
        // reliable is *where* text sits: directly inside a message, and outside any argument's header.
        final Deque<Boolean> readable = new ArrayDeque<>();
        readable.push(Boolean.FALSE);

        for (int i = 0; i < parsed.countParts(); i++) {
            final MessagePattern.Part part = parsed.getPart(i);
            final int index = part.getIndex();
            if (index > cursor) {
                final String text = pattern.substring(cursor, index);
                if (Boolean.TRUE.equals(readable.peek())) {
                    readableLength += text.length();
                }
                result.append(segment(text, style, readable.peek()));
            }
            result.append(pattern, index, part.getLimit());
            cursor = part.getLimit();
            track(part, readable);
        }
        // No tail to handle: a parsed pattern always ends with its MSG_LIMIT part, so the loop above has
        // already consumed everything.
        return wrap(result.toString(), style, readableLength);
    }

    /**
     * Marks a disfigured message as one, in whichever way its style is recognised by.
     *
     * <p>Applied once around the whole message rather than around each run of readable text. Inserted
     * mid-pattern the padding lands next to whatever follows, and beside an apostrophe — MessageFormat's
     * quoting character — that changes how the rest of the pattern is read. Measured: it turned two
     * SKIP_SYNTAX parts into INSERT_CHAR ones, which is a different message, silently.</p>
     *
     * @param message the message with its readable text already disfigured
     * @param style which pseudo-locale is being produced
     * @param readableLength how much of the message a reader actually sees
     * @return the message, marked
     */
    private static String wrap(final String message, final Style style, final int readableLength)
    {
        if (style == Style.ACCENTED) {
            return "[" + message + padding(readableLength) + "]";
        }
        return RIGHT_TO_LEFT_OVERRIDE + message + POP_DIRECTION;
    }

    /**
     * Follows the pattern's structure so that {@link #transform} knows whether the text it is looking at is
     * read by a person or by the formatter.
     *
     * @param part the part just passed
     * @param readable the nesting of message bodies and argument headers, innermost on top
     */
    private static void track(final MessagePattern.Part part, final Deque<Boolean> readable)
    {
        final MessagePattern.Part.Type type = part.getType();
        if (type == MessagePattern.Part.Type.MSG_START) {
            readable.push(Boolean.TRUE);
        } else if (type == MessagePattern.Part.Type.ARG_START) {
            readable.push(Boolean.FALSE);
        } else if (type == MessagePattern.Part.Type.MSG_LIMIT || type == MessagePattern.Part.Type.ARG_LIMIT) {
            readable.pop();
        }
    }

    private static String segment(final String text, final Style style, final boolean readable)
    {
        return readable ? disfigure(text, style) : text;
    }

    private static String disfigure(final String text, final Style style)
    {
        if (style == Style.SHORTENED) {
            return reopenPerLine(shorten(text));
        }
        final StringBuilder accented = new StringBuilder(text.length());
        for (final char c : text.toCharArray()) {
            final int letter = PLAIN.indexOf(c);
            // Letter for letter, so the text keeps its exact length here and nothing shifts. Anything that
            // is not a plain letter -- an apostrophe above all -- is copied untouched.
            accented.append(letter < 0 ? c : ACCENTED.charAt(letter));
        }
        return accented.toString();
    }

    /**
     * The run of extra characters that makes the accented form as long as a wordier language would be.
     *
     * @param readableLength how much of the message a reader actually sees
     * @return padding, empty when there is nothing to lengthen
     */
    private static String padding(final int readableLength)
    {
        final int extra = (int) Math.round(readableLength * (EXPANSION - 1));
        return extra <= 0 ? "" : " " + "·".repeat(extra);
    }

    /**
     * The text with its vowels removed, which is how the shortened form gets shorter.
     *
     * <p>Word by word rather than letter by letter, so that a word made only of vowels -- "a", "I" --
     * leaves something behind instead of vanishing and gluing its neighbours into a different
     * sentence.</p>
     *
     * @param text a run of text a reader sees
     * @return the same text, shorter
     */
    private static String shorten(final String text)
    {
        final StringBuilder result = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            if (!Character.isLetter(text.charAt(index))) {
                // Everything that is not a letter stays exactly where it was: Markdown delimiters,
                // apostrophes, braces, whitespace. That is what makes this safe on text with markup in it.
                result.append(text.charAt(index));
                index++;
                continue;
            }
            int end = index;
            while (end < text.length() && Character.isLetter(text.charAt(end))) {
                end++;
            }
            result.append(devowel(text, index, end));
            index = end;
        }
        return result.toString();
    }

    /**
     * One word without its vowels.
     *
     * @param text the text being disfigured
     * @param from where the word starts
     * @param to where it ends
     * @return the word's consonants, or its first letter where it has none
     */
    private static String devowel(final String text, final int from, final int to)
    {
        final StringBuilder word = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            if (VOWELS.indexOf(text.charAt(i)) < 0) {
                word.append(text.charAt(i));
            }
        }
        return word.length() == 0 ? text.substring(from, from + 1) : word.toString();
    }

    /**
     * Closes and re-opens the direction override around every line break.
     *
     * <p>A direction override reaches as far as the end of its <em>bidi paragraph</em>, and a forced line
     * break ends one -- so a {@code <br>}, or a blank line that Markdown turns into a second paragraph,
     * drops the override and everything after it reads left to right again. One override around the whole
     * message therefore turned only its first line around, which is worse than turning none of it: the
     * layout looks checked, and the half that was not checked looks exactly like the half that was.</p>
     *
     * <p>Done here, on text a reader sees, rather than on the finished pattern. A newline can also fall
     * inside an argument's header -- a {@code plural} and its categories written across several lines --
     * and inserting anything there would rewrite the message instead of restyling it.</p>
     *
     * @param text a run of text a reader sees, already shortened
     * @return the same text, with each of its lines overridden in its own right
     */
    private static String reopenPerLine(final String text)
    {
        return text.replace("\n", POP_DIRECTION + "\n" + RIGHT_TO_LEFT_OVERRIDE);
    }

    /**
     * The sequence of part types a pattern is made of, ignoring the text between them.
     *
     * <p>This is the check that matters when generating a pseudo-locale: a transform that damages syntax
     * usually still produces something that parses, just into a different shape, so "it still parses" proves
     * very little. Comparing the skeletons proves that only readable text changed.</p>
     *
     * @param pattern an ICU MessageFormat pattern
     * @return its structure, as a list of part types
     */
    public static List<String> skeleton(final String pattern)
    {
        final MessagePattern parsed = new MessagePattern(pattern);
        final List<String> types = new ArrayList<>(parsed.countParts());
        for (int i = 0; i < parsed.countParts(); i++) {
            types.add(parsed.getPart(i).getType().name());
        }
        return types;
    }
}
