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
import java.util.regex.Matcher;

/**
 * A parsed document, prepared once for every quote that will be checked against it.
 *
 * <p>Normalized once, since every quote of a reading is checked against the same text. The normalized form has
 * to be what {@link QuoteVerifier#normalize} makes of the whole document, or a faithful quote is not found.</p>
 *
 * <p>Immutable, and safe to share between callers.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DocumentScan
{
    /** A scan of nothing, for a document that could not be read. */
    static final DocumentScan EMPTY = new DocumentScan("");

    /** The document as it was parsed, which is what goes to the model. */
    private final String text;

    /** The whole document normalized, which is what a quote is compared against. */
    private final String normalized;

    /** How far into {@link #normalized} each line ends, so a position in it says which line it is on. */
    private final int[] lineEndsAt;

    /** Every heading title in the document, in order. */
    private final List<String> headings;

    /** Which of {@link #headings} is the nearest above each line, or -1 where there is none. */
    private final int[] headingAbove;

    /** Where in {@link #normalized} each of several documents sent together starts, empty for a single one. */
    private final int[] partStartsAt;

    private DocumentScan(final String text)
    {
        this(text, new int[0]);
    }

    /**
     * Prepare a text, remembering where each of the documents joined into it starts.
     *
     * @param text the whole text
     * @param partLines the line each document starts on, when several were joined
     */
    private DocumentScan(final String text, final int[] partLines)
    {
        this.text = text;
        final int lines = countLines(text);
        this.lineEndsAt = new int[lines];
        this.headingAbove = new int[lines];
        this.headings = new ArrayList<>();
        final StringBuilder normalizedText = new StringBuilder(text.length());
        int from = 0;
        for (int line = 0; line < lines; line++) {
            final int breakAt = text.indexOf('\n', from);
            final int to = breakAt < 0 ? text.length() : breakAt;
            final String lineText = text.substring(from, to);
            // No separator for a blank line, or a quote spanning a paragraph break would never match
            final String normalizedLine = QuoteVerifier.normalize(lineText);
            if (!normalizedLine.isEmpty()) {
                normalizedText.append(normalizedLine).append(' ');
            }
            this.lineEndsAt[line] = normalizedText.length();
            final Matcher isHeading = DocumentText.matchHeading(lineText);
            if (isHeading.matches()) {
                this.headings.add(isHeading.group(1).strip());
            }
            this.headingAbove[line] = this.headings.size() - 1;
            from = to + 1;
        }
        this.normalized = normalizedText.toString();
        this.partStartsAt = new int[partLines.length];
        for (int part = 0; part < partLines.length; part++) {
            this.partStartsAt[part] = partLines[part] == 0 ? 0 : this.lineEndsAt[partLines[part] - 1];
        }
    }

    /**
     * Several documents as one text, each under a heading of its own, remembering where each one starts so a
     * quote can be traced back to the document it came from.
     *
     * @param headings the heading each document goes under
     * @param texts the documents, in the same order
     * @return the scan of the joined text
     */
    static DocumentScan joined(final List<String> headings, final List<String> texts)
    {
        final StringBuilder text = new StringBuilder();
        final int[] partLines = new int[texts.size()];
        for (int part = 0; part < texts.size(); part++) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            partLines[part] = countLines(text.toString()) - 1;
            text.append("# ").append(headings.get(part)).append("\n\n").append(texts.get(part));
        }
        return new DocumentScan(text.toString(), partLines);
    }

    /**
     * Which of the documents joined into this text a position falls in.
     *
     * @param at a position from {@link #locate}
     * @return the document's index, or -1 when the text is a single document or the position is nowhere
     */
    int getPart(final int at)
    {
        int found = -1;
        for (int part = 0; part < this.partStartsAt.length && at >= 0; part++) {
            if (this.partStartsAt[part] <= at) {
                found = part;
            }
        }
        return found;
    }

    /**
     * Prepare a document for checking quotes against.
     *
     * @param text the parsed document, may be {@code null}
     * @return the scan, {@link #EMPTY} when there is nothing to scan
     */
    static DocumentScan of(final String text)
    {
        return text == null || text.isEmpty() ? EMPTY : new DocumentScan(text);
    }

    /**
     * The document as it was parsed.
     *
     * @return the text
     */
    String getText()
    {
        return this.text;
    }

    /**
     * Whether there is nothing here to read or to check against.
     *
     * @return {@code true} when the document is blank
     */
    boolean isBlank()
    {
        return this.text.isBlank();
    }

    /**
     * Where a quote is in this document, allowing for the small slips a model makes when copying.
     *
     * @param quote what the model quoted
     * @return a position to pass to {@link #getHeading}, or -1 when enough of the quote is not there
     */
    int locate(final String quote)
    {
        return QuoteVerifier.locateIn(quote, this.normalized, this.partStartsAt);
    }

    /**
     * The nearest ATX heading above a position, which is where a reader would say the quote lives.
     *
     * @param at a position from {@link #locate}
     * @return the heading's title, or an empty string when nothing titles it
     */
    String getHeading(final int at)
    {
        final int line = getLine(at);
        return line < 0 || this.headingAbove[line] < 0 ? "" : this.headings.get(this.headingAbove[line]);
    }

    /**
     * Which line a position in the normalized text is on: the first whose end is past it.
     *
     * <p>A binary search: {@link #lineEndsAt} only ever climbs.</p>
     *
     * @param at the position, or -1 for nowhere
     * @return the line index, or -1 when the position is nowhere
     */
    private int getLine(final int at)
    {
        if (at < 0) {
            return -1;
        }
        int low = 0;
        int high = this.lineEndsAt.length - 1;
        while (low < high) {
            final int middle = (low + high) >>> 1;
            if (this.lineEndsAt[middle] <= at) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /**
     * How many lines a text has, counting a trailing empty one.
     *
     * @param text the document
     * @return the number of lines, at least one
     */
    private static int countLines(final String text)
    {
        int lines = 1;
        for (int at = text.indexOf('\n'); at >= 0; at = text.indexOf('\n', at + 1)) {
            lines++;
        }
        return lines;
    }
}
