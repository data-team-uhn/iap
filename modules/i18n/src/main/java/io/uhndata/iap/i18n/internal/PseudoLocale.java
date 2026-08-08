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
        /** Shortened, unmarked: the "everything is shorter" case. */
        SHORTENED
    }

    /** Latin letters and the lookalikes they map to, in the same order. */
    private static final String PLAIN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final String ACCENTED = "áƀçðéƒĝĥíĵķĺɱñóþqŕšţúṽŵẋýžÁƁÇÐÉƑĜĤÍĴĶĹṀÑÓÞQŔŠŢÚṼŴẊÝŽ";

    /** How much longer the accented form runs, which is roughly what a German translation costs. */
    private static final double EXPANSION = 1.4;

    /** How much of the original the shortened form keeps. */
    private static final double CONTRACTION = 0.6;

    private PseudoLocale()
    {
        // Utility class
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
        final MessagePattern parsed = new MessagePattern(pattern);
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
        // The padding goes here, once, rather than after each run of text. Inserted mid-pattern it lands
        // next to whatever follows, and beside an apostrophe -- MessageFormat's quoting character -- that
        // changes how the rest of the pattern is read. Measured: it turned two SKIP_SYNTAX parts into
        // INSERT_CHAR ones, which is a different message, silently.
        return style == Style.ACCENTED ? "[" + result + padding(readableLength) + "]" : result.toString();
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
            return shorten(text);
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

    private static String shorten(final String text)
    {
        if (text.isBlank()) {
            return text;
        }
        final int keep = Math.max(1, (int) Math.round(text.length() * CONTRACTION));
        return text.substring(0, keep);
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
